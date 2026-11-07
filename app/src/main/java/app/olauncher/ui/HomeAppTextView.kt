package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** The zero-gap bottom row aligns visible glyphs, rather than the font's unused descender space. */
class HomeAppTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    var alignInkToBottom = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val ink = Rect()

    override fun onDraw(canvas: Canvas) {
        if (!alignInkToBottom || layout == null) { super.onDraw(canvas); return }
        val label = if (text.isNullOrEmpty()) hint.orEmpty() else text
        val available = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0).toFloat()
        val visible = TextUtils.ellipsize(label, paint, available, ellipsize ?: TextUtils.TruncateAt.END).toString()
        if (visible.isBlank()) { super.onDraw(canvas); return }
        paint.getTextBounds(visible, 0, visible.length, ink)
        var contentBottom = baseline + ink.bottom - scrollY
        // Keep a leading/trailing app icon fully visible as well as the text.
        val contentHeight = height - compoundPaddingTop - compoundPaddingBottom
        val drawables = compoundDrawablesRelative
        for (index in intArrayOf(0, 2)) drawables[index]?.let { icon ->
            contentBottom = maxOf(contentBottom, compoundPaddingTop + (contentHeight + icon.bounds.height()) / 2)
        }
        val save = canvas.save()
        canvas.translate(0f, (height - contentBottom).toFloat())
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}
