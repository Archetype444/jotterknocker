package app.olauncher.helper

/** Scrollbar geometry in viewport coordinates, independent of text layout and screen density. */
object NoteScrollThumb {
    data class Thumb(val top: Float, val height: Float)

    fun calculate(trackTop: Float, viewport: Float, content: Float, offset: Float,
                  minimum: Float, maximum: Float): Thumb? {
        if (viewport <= 0f || content <= viewport) return null
        val length = (viewport * (viewport / content))
            .coerceIn(minOf(minimum, viewport), minOf(maximum, viewport))
        val progress = (offset / (content - viewport)).coerceIn(0f, 1f)
        return Thumb(trackTop + progress * (viewport - length), length)
    }
}
