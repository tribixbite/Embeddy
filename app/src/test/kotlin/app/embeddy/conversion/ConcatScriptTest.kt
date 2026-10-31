package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ConcatScriptTest {

    @Test
    fun `script lists every frame with its duration and repeats the last file`() {
        val script = ConcatScript.build(
            listOf(ConcatScript.Entry("frame_00000.png", 100), ConcatScript.Entry("frame_00001.png", 1_500)),
        )
        assertEquals(
            "file 'frame_00000.png'\nduration 0.100\n" +
                "file 'frame_00001.png'\nduration 1.500\n" +
                "file 'frame_00001.png'\n",
            script,
        )
    }

    @Test
    fun `zero and tiny durations are raised to the minimum`() {
        val script = ConcatScript.build(listOf(ConcatScript.Entry("a.png", 0), ConcatScript.Entry("b.png", 5)))
        assertEquals(2, Regex("duration 0.020").findAll(script).count())
    }

    @Test
    fun `durations use a dot decimal separator under any locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(true, "duration 0.250" in ConcatScript.build(listOf(ConcatScript.Entry("a.png", 250))))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty frame list is rejected`() {
        ConcatScript.build(emptyList())
    }
}
