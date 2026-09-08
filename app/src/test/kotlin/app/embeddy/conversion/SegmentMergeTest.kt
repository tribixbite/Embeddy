package app.embeddy.conversion

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [mergeOverlappingSegments] — the PRODUCTION top-level function in
 * ConversionConfig.kt that MainViewModel calls before building FFmpeg commands.
 * (This suite previously tested a private mirror copy of the algorithm, which
 * let a production regression pass unnoticed.)
 */
class SegmentMergeTest {

    private fun mergeOverlapping(segments: List<TrimSegment>): List<TrimSegment> =
        mergeOverlappingSegments(segments)

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

    // ── zero-length segments (would create empty trim streams in the graph) ──

    @Test
    fun `zero-length segments are dropped`() {
        val input = listOf(
            TrimSegment(0, 2000),
            TrimSegment(3000, 3000),
            TrimSegment(4000, 6000),
        )
        val expected = listOf(TrimSegment(0, 2000), TrimSegment(4000, 6000))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `a single zero-length segment yields an empty list`() {
        assertEquals(emptyList<TrimSegment>(), mergeOverlapping(listOf(TrimSegment(5000, 5000))))
    }

    @Test
    fun `negative-length segments are dropped`() {
        val input = listOf(TrimSegment(0, 2000), TrimSegment(5000, 3000))
        assertEquals(listOf(TrimSegment(0, 2000)), mergeOverlapping(input))
    }

    // ── volume-aware merge semantics ──
    // Documented choice: overlapping segments merge FIRST-WINS (the earlier
    // segment's volume covers the whole merged range, matching how the merge
    // already keeps the earlier segment's start). Adjacent segments merge only
    // when their volumes are equal — a volume change at a cut point is an
    // intentional audio edit and must survive the merge.

    @Test
    fun `overlapping segments with different volumes merge first-wins`() {
        val input = listOf(
            TrimSegment(0, 3000, volume = 0.5f),
            TrimSegment(2000, 5000, volume = 1.5f),
        )
        val expected = listOf(TrimSegment(0, 5000, volume = 0.5f))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `adjacent segments with different volumes stay separate`() {
        val input = listOf(
            TrimSegment(0, 3000, volume = 1f),
            TrimSegment(3000, 6000, volume = 0.25f),
        )
        assertEquals(input, mergeOverlapping(input))
    }

    @Test
    fun `adjacent segments with equal volumes still merge`() {
        val input = listOf(
            TrimSegment(0, 3000, volume = 1.5f),
            TrimSegment(3000, 6000, volume = 1.5f),
        )
        val expected = listOf(TrimSegment(0, 6000, volume = 1.5f))
        assertEquals(expected, mergeOverlapping(input))
    }

    @Test
    fun `contained segment with a different volume disappears into the container`() {
        val input = listOf(
            TrimSegment(0, 10000, volume = 1f),
            TrimSegment(2000, 5000, volume = 2f),
        )
        assertEquals(listOf(TrimSegment(0, 10000, volume = 1f)), mergeOverlapping(input))
    }
}
