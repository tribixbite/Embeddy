package app.embeddy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [deleteStaleEntries] — the deletion primitive CleanupWorker uses.
 * The key regression: `frames_*` extraction directories are non-empty, and
 * [File.delete] returns false for those, so orphaned frame PNGs were never
 * reclaimed until the worker switched to deleteRecursively.
 */
class CleanupWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val past = System.currentTimeMillis() - 48 * 60 * 60 * 1000L

    @Test
    fun `stale non-empty directories are removed, which plain delete cannot do`() {
        val cacheDir = tmp.newFolder("temp")
        val frameDir = File(cacheDir, "frames_abc123").apply { mkdirs() }
        repeat(3) { File(frameDir, "frame_0000$it.png").writeBytes(ByteArray(16)) }
        frameDir.setLastModified(past)

        // Precondition documenting the bug: File.delete() fails on a non-empty dir
        assertFalse(frameDir.delete())
        assertTrue(frameDir.exists())

        val deleted = deleteStaleEntries(cacheDir, System.currentTimeMillis() - 24 * 60 * 60 * 1000L)

        assertEquals(1, deleted)
        assertFalse(frameDir.exists())
    }

    @Test
    fun `stale plain files are still removed`() {
        val cacheDir = tmp.newFolder("converted")
        val old = File(cacheDir, "old_embeddy.webp").apply {
            writeBytes(ByteArray(8))
            setLastModified(past)
        }

        assertEquals(1, deleteStaleEntries(cacheDir, System.currentTimeMillis()))
        assertFalse(old.exists())
    }

    @Test
    fun `fresh entries survive the cutoff`() {
        val cacheDir = tmp.newFolder("temp")
        val fresh = File(cacheDir, "input_now").apply { writeBytes(ByteArray(8)) }
        val freshDir = File(cacheDir, "frames_now").apply { mkdirs() }
        File(freshDir, "frame_00000.png").writeBytes(ByteArray(8))

        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        assertEquals(0, deleteStaleEntries(cacheDir, cutoff))
        assertTrue(fresh.exists())
        assertTrue(freshDir.exists())
    }

    @Test
    fun `a missing directory deletes nothing and does not throw`() {
        assertEquals(0, deleteStaleEntries(File(tmp.root, "does_not_exist"), System.currentTimeMillis()))
    }
}
