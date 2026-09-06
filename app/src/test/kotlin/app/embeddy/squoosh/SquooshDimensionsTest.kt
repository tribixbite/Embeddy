package app.embeddy.squoosh

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [orientedDimensions] — the pure half of the M69 fix: SquooshResult
 * must report the TRUE bounds-pass source dimensions, swapped for EXIF
 * orientations in the 90°/270° family.
 */
class SquooshDimensionsTest {

    @Test
    fun `rotate 90 swaps width and height`() {
        assertEquals(
            3024 to 4032,
            orientedDimensions(ExifInterface.ORIENTATION_ROTATE_90, 4032, 3024),
        )
    }

    @Test
    fun `rotate 270 swaps width and height`() {
        assertEquals(
            3024 to 4032,
            orientedDimensions(ExifInterface.ORIENTATION_ROTATE_270, 4032, 3024),
        )
    }

    @Test
    fun `transpose and transverse swap width and height`() {
        assertEquals(
            100 to 200,
            orientedDimensions(ExifInterface.ORIENTATION_TRANSPOSE, 200, 100),
        )
        assertEquals(
            100 to 200,
            orientedDimensions(ExifInterface.ORIENTATION_TRANSVERSE, 200, 100),
        )
    }

    @Test
    fun `normal, 180, and flips keep dimensions`() {
        listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_UNDEFINED,
        ).forEach { orientation ->
            assertEquals(
                "orientation $orientation must not swap",
                4032 to 3024,
                orientedDimensions(orientation, 4032, 3024),
            )
        }
    }
}
