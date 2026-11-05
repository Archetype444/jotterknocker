package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NoteScrollThumbTest {
    @Test fun shortNotesHaveNoScrollbar() {
        assertNull(NoteScrollThumb.calculate(56f, 400f, 400f, 0f, 12f, 56f))
        assertNull(NoteScrollThumb.calculate(56f, 0f, 400f, 0f, 12f, 56f))
    }

    @Test fun endpointsAreExactAndLengthShrinksWithContent() {
        val top = NoteScrollThumb.calculate(56f, 400f, 800f, 0f, 12f, 56f)!!
        val bottom = NoteScrollThumb.calculate(56f, 400f, 800f, 400f, 12f, 56f)!!
        assertEquals(56f, top.top, 0f)
        assertEquals(456f, bottom.top + bottom.height, 0.001f)
        assertEquals(56f, top.height, 0f)
        assertEquals(12f, NoteScrollThumb.calculate(56f, 400f, 100000f, 0f, 12f, 56f)!!.height, 0f)
    }

    @Test fun everyOffsetStaysInsideTheNoteIncludingTinyViewportsAndOverscroll() {
        val random = Random(73)
        repeat(10000) {
            val viewport = random.nextFloat() * 2000 + 1
            val content = viewport + random.nextFloat() * 100000 + 1
            val offset = random.nextFloat() * content * 2 - viewport
            val thumb = NoteScrollThumb.calculate(56f, viewport, content, offset, 12f, 56f)!!
            assertTrue(thumb.top >= 56f)
            assertTrue(thumb.top + thumb.height <= 56f + viewport + 0.001f)
            assertTrue(thumb.height <= minOf(viewport, 56f))
            assertTrue(thumb.height >= minOf(viewport, 12f))
        }
    }

    @Test fun thumbPositionTracksTheFractionOfScrollableContent() {
        val middle = NoteScrollThumb.calculate(10f, 500f, 5000f, 2250f, 12f, 56f)!!
        assertEquals(10f + (500f - middle.height) / 2, middle.top, 0.001f)
    }
}
