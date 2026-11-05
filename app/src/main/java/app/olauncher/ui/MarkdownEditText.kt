package app.olauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import app.olauncher.helper.NoteScrollThumb
import kotlin.math.abs
import kotlin.math.roundToInt
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatEditText
import app.olauncher.helper.CheckboxSpan
import app.olauncher.helper.LinkSpan

/**
 * Scratchpad [AppCompatEditText] that additionally detects taps landing on a rendered
 * [CheckboxSpan] glyph and toggles the underlying `[ ]`/`[x]` markdown text in place.
 * While not editing, a tap on a [LinkSpan] opens the link instead of focusing.
 * Reading gestures never reach EditText: only a confirmed tap starts editing.
 * Read-mode drags and flings keep the rendered markdown layout stable.
 */
class MarkdownEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Pinch-to-zoom callback: cumulative scale since the pinch began, and whether the pinch ended. */
    var onPinch: ((scale: Float, done: Boolean) -> Unit)? = null
    private var pinch = 1f
    private var pinching = false
    /** The touch went down on a checkbox or link: its long-press must not open settings (or select text). */
    private var downOnTapTarget = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinch = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch *= detector.scaleFactor
            onPinch?.invoke(pinch, false)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { onPinch?.invoke(pinch, true) }
    })

    private val configuration = ViewConfiguration.get(context)
    private val scroller = OverScroller(context).apply {
        // A little less drag than Android's default, with real velocity and bounded deceleration.
        setFriction(ViewConfiguration.getScrollFriction() * 0.65f)
    }
    private var velocity: VelocityTracker? = null
    private var readingTouch = false
    private var dragging = false
    private var moved = false
    private var held = false
    private var nativeMoved = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var scrollRemainder = 0f
    private var downCheckbox: CheckboxSpan? = null
    private var downLink: LinkSpan? = null
    private var thumbAlpha = 0f
    private var fade: ValueAnimator? = null
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get() = resources.displayMetrics.density
    private val longPress = Runnable {
        if (readingTouch && !moved && !pinching) {
            held = true
            performLongClick()
        }
    }
    private val fadeThumb = Runnable {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(thumbAlpha, 0f).apply {
            duration = 180
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    val maximumNoteScroll: Int
        get() = maxOf(0, (layout?.height ?: 0) - maxOf(0, height - totalPaddingTop - totalPaddingBottom))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pinching = false
            nativeMoved = false
            downX = event.x
            downY = event.y
            downCheckbox = checkboxAt(event)
            downOnTapTarget = checkboxAt(event) != null || linkAt(event) != null
        }
        if (onPinch != null) {
            scaleDetector.onTouchEvent(event)
            if (!pinching && event.pointerCount > 1) {
                pinching = true
                stopFling()
                endReadingTouch()
                cancelTouch(event)
            }
            if (pinching) return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            readingTouch = !isFocused
            if (readingTouch) {
                // Touching a moving note stops it; lifting that finger must not start editing.
                moved = !scroller.isFinished
                stopFling()
                dragging = false
                held = false
                downX = event.x
                downY = event.y
                lastY = event.y
                lastMoveTime = event.eventTime
                scrollRemainder = 0f
                downCheckbox = checkboxAt(event)
                downLink = linkAt(event)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                if (!moved) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
        }
        if (!readingTouch) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                (abs(event.x - downX) > configuration.scaledTouchSlop || abs(event.y - downY) > configuration.scaledTouchSlop)) nativeMoved = true
            if (event.actionMasked == MotionEvent.ACTION_UP && !nativeMoved && downCheckbox != null && checkboxAt(event) === downCheckbox) {
                val span = downCheckbox!!
                val index = text!!.getSpanStart(span) + 1
                text!!.replace(index, index + 1, if (span.checked) " " else "x")
                cancelTouch(event)
                return true
            }
            return super.onTouchEvent(event)
        }
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (event.y != lastY) lastMoveTime = event.eventTime
                if (!moved && (abs(dx) > configuration.scaledTouchSlop || abs(dy) > configuration.scaledTouchSlop)) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (!dragging && moved && abs(dy) > configuration.scaledTouchSlop && abs(dy) > abs(dx)) {
                    dragging = true
                    // Remove only the recognition slop; preserve the rest of the first move.
                    lastY = downY + if (dy > 0) configuration.scaledTouchSlop else -configuration.scaledTouchSlop
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val delta = lastY - event.y + scrollRemainder
                    val pixels = delta.toInt()
                    scrollRemainder = delta - pixels
                    scrollTo(0, (scrollY + pixels).coerceIn(0, maximumNoteScroll))
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    // Older VelocityTrackers can keep a stale velocity if the finger pauses before lifting.
                    val speed = if (event.eventTime - lastMoveTime <= 40L) {
                        velocity?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                        -(velocity?.yVelocity ?: 0f).roundToInt()
                    } else 0
                    if (abs(speed) >= configuration.scaledMinimumFlingVelocity) {
                        scroller.fling(0, scrollY, 0, speed, 0, 0, 0, maximumNoteScroll)
                        postInvalidateOnAnimation()
                    } else fadeNow()
                } else if (!moved && !held) {
                    when {
                        downCheckbox != null && checkboxAt(event) === downCheckbox -> {
                            val span = downCheckbox!!
                            val index = text!!.getSpanStart(span) + 1
                            text!!.replace(index, index + 1, if (span.checked) " " else "x")
                        }
                        downLink != null && linkAt(event) === downLink -> openLink(downLink!!.url)
                        else -> beginEditing(event)
                    }
                } else fadeNow()
                endReadingTouch()
            }
            MotionEvent.ACTION_CANCEL -> { endReadingTouch(); fadeNow() }
        }
        return true
    }

    private fun beginEditing(event: MotionEvent) {
        val offset = layout?.let { offsetForTouch(it, event.x, event.y) }
        requestFocus()
        isCursorVisible = true
        if (offset != null) setSelection(offset.coerceIn(0, length()))
        performClick()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun endReadingTouch() {
        readingTouch = false
        removeCallbacks(longPress)
        velocity?.recycle()
        velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun stopFling() { scroller.forceFinished(true) }

    val isReadingScrollActive get() = readingTouch || !scroller.isFinished

    fun stopReadingScroll() {
        stopFling()
        endReadingTouch()
        fadeNow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldh > 0 && (w != oldw || h != oldh)) {
            // A layout change can interrupt momentum, but an ongoing drag still owns its touch stream.
            stopFling()
            fadeNow()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (scrollY > maximumNoteScroll) scrollTo(0, maximumNoteScroll)
    }

    private fun fadeNow() {
        removeCallbacks(fadeThumb)
        if (thumbAlpha > 0f) post(fadeThumb)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY.coerceIn(0, maximumNoteScroll))
            if (!scroller.isFinished) postInvalidateOnAnimation() else fadeNow()
        } else super.computeScroll()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt && maximumNoteScroll > 0) {
            fade?.cancel()
            thumbAlpha = 1f
            removeCallbacks(fadeThumb)
            postDelayed(fadeThumb, 80)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbAlpha <= 0f) return
        val viewport = (height - totalPaddingTop - totalPaddingBottom).toFloat()
        val thumb = NoteScrollThumb.calculate(totalPaddingTop.toFloat(), viewport,
            (layout?.height ?: 0).toFloat(), scrollY.toFloat(), 12f * density, 56f * density) ?: return
        val checkpoint = canvas.save()
        // TextView draws in scrolled coordinates; the indicator stays fixed to its own viewport.
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.clipRect(0, 0, width, height)
        thumbPaint.color = currentTextColor
        thumbPaint.alpha = (thumbAlpha * 190).roundToInt().coerceIn(0, 255)
        val left = 9f * density
        val thickness = 2.5f * density
        canvas.drawRoundRect(left, thumb.top, left + thickness, thumb.top + thumb.height,
            thickness / 2, thickness / 2, thumbPaint)
        canvas.restoreToCount(checkpoint)
    }

    override fun onDetachedFromWindow() {
        stopFling()
        endReadingTouch()
        removeCallbacks(fadeThumb)
        fade?.cancel()
        super.onDetachedFromWindow()
    }

    private fun cancelTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    // A slow tap can outlast the long-press timeout before UP arrives; never treat it as a long-press.
    override fun performLongClick(): Boolean = downOnTapTarget || super.performLongClick()

    // By drawn position, not text offset: the hidden zero-width "- " before the box would swallow taps on its left half.
    // Half a box of slack on each side for fingers.
    private fun checkboxAt(event: MotionEvent): CheckboxSpan? {
        val editable = text ?: return null
        val currentLayout = layout ?: return null
        val x = event.x - totalPaddingLeft + scrollX
        val y = (event.y - totalPaddingTop + scrollY).toInt()
        if (event.y < totalPaddingTop || event.y >= height - totalPaddingBottom || y !in 0 until currentLayout.height) return null
        val line = currentLayout.getLineForVertical(y)
        return editable.getSpans(currentLayout.getLineStart(line), currentLayout.getLineEnd(line), CheckboxSpan::class.java).firstOrNull {
            val start = currentLayout.getPrimaryHorizontal(editable.getSpanStart(it))
            val end = currentLayout.getPrimaryHorizontal(editable.getSpanEnd(it))
            val slack = (end - start) / 2
            x in start - slack..end + slack
        }
    }

    private fun linkAt(event: MotionEvent): LinkSpan? {
        if (isFocused) return null
        val editable = text ?: return null
        val offset = layout?.let { offsetForTouch(it, event.x, event.y) } ?: return null
        return editable.getSpans(offset, offset, LinkSpan::class.java)
            .firstOrNull { editable.getSpanStart(it) <= offset && offset < editable.getSpanEnd(it) }
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url).let { if (it.scheme == null) Uri.parse("https://$url") else it }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun offsetForTouch(currentLayout: android.text.Layout, touchX: Float, touchY: Float): Int {
        val x = (touchX - totalPaddingLeft + scrollX).toInt()
        val y = (touchY - totalPaddingTop + scrollY).toInt()
        val line = currentLayout.getLineForVertical(y)
        return currentLayout.getOffsetForHorizontal(line, x.toFloat())
    }
}
