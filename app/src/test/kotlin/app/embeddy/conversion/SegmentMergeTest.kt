package app.embeddy.conversion

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for segment overlap merging logic.
 * The merge algorithm is extracted here for testability — mirrors the
 * implementation in MainViewModel.mergeOverlappingSegments().
 */
class SegmentMergeTest {

    /** Merge overlapping segments — same algorithm as MainViewModel. */
    private fun mergeOverlapping(segments: List<TrimSegment>): List<TrimSegment> {
        if (segments.size <= 1) return segments
        val sorted = segments.sortedBy { it.startMs }
        return sorted.fold(mutableListOf<TrimSegment>()) { acc, seg ->
            val last = acc.lastOrNull()
            if (last != null && seg.startMs <= last.endMs) {
                acc[acc.lastIndex] = TrimSegment(last.startMs, maxOf(last.endMs, seg.endMs))
            } else {
                acc.add(seg)
            }
            acc
        }
    }

    @Test
    fun `single segment unchanged`() {
        val input = listOf(TrimSegment(0, 5000))
        assertEquals(input, mergeOverlapping(input))
    }

    @Test
    fun `non-overlapping segments unchanged`() {
        val input = listOf(
            TrimSegment(0, 2000),
            TrimSegment(3000, 5000),
            TrimSegment(7000, 10000),
        )
        assertEquals(input, mergeOverlapping(input))
    }

    @Test
    fun `overlapping segments merged`() {
        val input = listOf(
            TrimSegment(0, 3000),
            TrimSegment(2000, 5000),
        )
        val expected = listOf(TrimSegment(0, 5000))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `adjacent segments merged`() {
        val input = listOf(
            TrimSegment(0, 3000),
            TrimSegment(3000, 6000),
        )
        val expected = listOf(TrimSegment(0, 6000))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `contained segment merged`() {
        val input = listOf(
            TrimSegment(0, 10000),
            TrimSegment(2000, 5000),
        )
        val expected = listOf(TrimSegment(0, 10000))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `unsorted segments sorted then merged`() {
        val input = listOf(
            TrimSegment(5000, 8000),
            TrimSegment(0, 3000),
            TrimSegment(2000, 6000),
        )
        // After sort: [0,3000], [2000,6000], [5000,8000]
        // Merge: [0,6000], [5000,8000] → [0,8000]
        val expected = listOf(TrimSegment(0, 8000))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `multiple groups merge independently`() {
        val input = listOf(
            TrimSegment(0, 2000),
            TrimSegment(1500, 3000),
            TrimSegment(5000, 7000),
            TrimSegment(6000, 9000),
        )
        val expected = listOf(
            TrimSegment(0, 3000),
            TrimSegment(5000, 9000),
        )
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `empty list returns empty`() {
        assertEquals(emptyList<TrimSegment>(), mergeOverlapping(emptyList()))
    }
}
