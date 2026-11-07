package app.olauncher.notifications

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShadePlayback(
    val packageName: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val actions: Long,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val speed: Float,
) {
    fun supports(action: Long) = actions and action != 0L
    val canSeek get() = position >= 0 && supports(PlaybackState.ACTION_SEEK_TO)
}

/** Event driven: no progress animation or polling that would continually refresh an e-ink panel. */
object ShadeMedia {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private val controllers = linkedMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val _playback = MutableStateFlow<ShadePlayback?>(null)
    val playback = _playback.asStateFlow()
    private var pendingSeek: Pair<Long, Long>? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { updateControllers(it.orEmpty()) }

    fun connect(context: Context, listener: ComponentName) {
        disconnect()
        manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            updateControllers(manager?.getActiveSessions(listener).orEmpty())
        }.onFailure { disconnect() }
    }

    fun disconnect() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controllers.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
        handler.removeCallbacksAndMessages(null)
        manager = null
        current = null
        pendingSeek = null
        _playback.value = null
    }

    private fun updateControllers(list: List<MediaController>) {
        val live = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in live }.toList().forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        }
        list.filter { it.sessionToken !in controllers }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    controllers.remove(controller.sessionToken)?.let { runCatching { controller.unregisterCallback(it.second) } }
                    publish()
                }
            }
            runCatching { controller.registerCallback(callback, handler); controllers[controller.sessionToken] = controller to callback }
        }
        publish()
    }

    private fun publish() {
        fun state(controller: MediaController) = runCatching { controller.playbackState }.getOrNull()
        val list = controllers.values.map { it.first }
        val selected = list.firstOrNull { state(it)?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == current?.sessionToken && state(it)?.state in listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
            ?: list.firstOrNull { state(it)?.state in listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
        if (selected?.sessionToken != current?.sessionToken) pendingSeek = null
        current = selected
        if (selected == null) { _playback.value = null; return }
        runCatching {
            val s = selected.playbackState ?: return@runCatching
            val m = selected.metadata
            val pending = pendingSeek
            val now = SystemClock.elapsedRealtime()
            if (pending != null && (now - pending.second > 1500 ||
                    (s.lastPositionUpdateTime >= pending.second && kotlin.math.abs(s.position - pending.first) < 2000))) pendingSeek = null
            val seek = pendingSeek
            _playback.value = ShadePlayback(selected.packageName,
                m?.description?.title?.toString().orEmpty(), m?.description?.subtitle?.toString().orEmpty(),
                s.state == PlaybackState.STATE_PLAYING, s.actions, seek?.first ?: s.position,
                m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                seek?.second ?: s.lastPositionUpdateTime, s.playbackSpeed)
        }.onFailure { _playback.value = null }
    }

    fun playPause(): Boolean = command {
        val s = _playback.value ?: return@command false
        val desired = if (s.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        if (!s.supports(desired) && !s.supports(PlaybackState.ACTION_PLAY_PAUSE)) return@command false
        if (s.playing) it.pause() else it.play()
        true
    }

    fun previous(): Boolean = command {
        if (_playback.value?.supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS) != true) return@command false
        it.skipToPrevious(); true
    }
    fun next(): Boolean = command {
        if (_playback.value?.supports(PlaybackState.ACTION_SKIP_TO_NEXT) != true) return@command false
        it.skipToNext(); true
    }
    fun seekBy(delta: Long): Boolean = command {
        val s = _playback.value ?: return@command false
        if (!s.canSeek) return@command false
        val now = SystemClock.elapsedRealtime()
        val position = MediaSeek.position(s.position, s.updatedAt, s.speed, s.playing, now) ?: return@command false
        val target = MediaSeek.target(position, delta, s.duration)
        it.seekTo(target)
        pendingSeek = target to now
        _playback.value = s.copy(position = target, updatedAt = now)
        handler.postDelayed({ publish() }, 1600)
        true
    }
    private fun command(action: (MediaController.TransportControls) -> Boolean): Boolean =
        runCatching { current?.transportControls?.let(action) ?: false }.getOrDefault(false)
}
