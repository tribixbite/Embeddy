package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EditSpec.keptRanges] — the single "what is kept" answer — and [previewEdit]. */
class EditSpecTest {

    @Test
    fun `untrimmed edit keeps the whole source`() {
        assertEquals(listOf(TrimSegment(0, 10_000)), EditSpec().keptRanges(10_000))
        assertEquals(10_000L, EditSpec().keptDurationMs(10_000))
        assertFalse(EditSpec().isTrimmed(10_000))
    }

    @Test
    fun `open trim end resolves to the source duration`() {
        val edit = EditSpec(trimStartMs = 2_000)
        assertEquals(listOf(TrimSegment(2_000, 10_000)), edit.keptRanges(10_000))
        assertTrue(edit.isTrimmed(10_000))
    }

    @Test
    fun `unknown source duration with no explicit end is an open range`() {
        val ranges = EditSpec(trimStartMs = 1_000).keptRanges(0)
        assertEquals(listOf(TrimSegment(1_000, 0)), ranges)
        assertEquals(0L, EditSpec().keptDurationMs(0))
    }

    @Test
    fun `inverted trim keeps nothing`() {
        assertTrue(EditSpec(trimStartMs = 5_000, trimEndMs = 3_000).keptRanges(10_000).isEmpty())
    }

    @Test
    fun `segments override the trim range and are merged`() {
        val edit = EditSpec(
            trimStartMs = 0,
            trimEndMs = 10_000,
            segments = listOf(TrimSegment(5_000, 8_000), TrimSegment(0, 3_000), TrimSegment(2_000, 4_000)),
        )
        assertEquals(listOf(TrimSegment(0, 4_000), TrimSegment(5_000, 8_000)), edit.keptRanges(10_000))
        assertEquals(7_000L, edit.keptDurationMs(10_000))
    }

    @Test
    fun `preview window cuts the first range to the window`() {
        val window = previewEdit(EditSpec(trimStartMs = 4_000, trimEndMs = 20_000), 30_000, 3_000)!!
        assertEquals(listOf(TrimSegment(4_000, 7_000)), window.keptRanges(30_000))
    }

    @Test
    fun `preview window respects a trim end shorter than the window`() {
        // Previously the preview ran 3 s past a 1 s trim, showing cut frames.
        val window = previewEdit(EditSpec(trimStartMs = 4_000, trimEndMs = 5_000), 30_000, 3_000)!!
        assertEquals(listOf(TrimSegment(4_000, 5_000)), window.keptRanges(30_000))
    }

    @Test
    fun `preview window spans segment gaps without including them`() {
        val edit = EditSpec(segments = listOf(TrimSegment(0, 1_000, 0.5f), TrimSegment(5_000, 9_000)))
        val window = previewEdit(edit, 10_000, 3_000)!!
        assertEquals(
            listOf(TrimSegment(0, 1_000, 0.5f), TrimSegment(5_000, 7_000)),
            window.keptRanges(10_000),
        )
    }

    @Test
    fun `preview of an edit that keeps nothing is null`() {
        assertNull(previewEdit(EditSpec(trimStartMs = 5_000, trimEndMs = 5_000), 10_000, 3_000))
    }

    @Test
    fun `preview of an unknown-duration source takes the window from the start`() {
        val window = previewEdit(EditSpec(), 0, 3_000)!!
        assertEquals(listOf(TrimSegment(0, 3_000)), window.keptRanges(0))
    }
}
