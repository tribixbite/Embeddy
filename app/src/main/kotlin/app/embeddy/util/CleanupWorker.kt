package app.embeddy.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber
import java.io.File

/**
 * Periodic worker that cleans up cached conversion/compression output
 * older than 24 hours. Scheduled via WorkManager in [app.embeddy.EmbeddyApp].
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        var deleted = 0

        // Clean every cache subdirectory the engines write to. Keep this list in sync
        // with ConversionEngine, SquooshEngine, UploadEngine and MetadataEngine.
        CACHE_DIRS.forEach { dirName ->
            deleted += deleteStaleEntries(File(applicationContext.cacheDir, dirName), cutoff)
        }

        Timber.d("CleanupWorker: deleted %d stale cache entries", deleted)
        return Result.success()
    }

    private companion object {
        val CACHE_DIRS = listOf(
            "converted",     // ConversionEngine final output
            "temp",          // ConversionEngine input copies + previews + frames_* dirs
            "squoosh_out",   // SquooshEngine output
            "upload_temp",   // UploadEngine staged uploads
            "inspect_temp",  // MetadataEngine FFprobe copies
        )
    }
}

/**
 * Delete every entry in [dir] whose lastModified is older than [cutoffMs].
 *
 * Uses [File.deleteRecursively] because conversions killed mid-run leave
 * `frames_*` DIRECTORIES full of PNGs behind — plain [File.delete] returns
 * false for non-empty directories, so hundreds of MB of orphaned frames were
 * never reclaimed by the periodic worker.
 *
 * Top-level and framework-free so the JVM unit suite exercises the exact
 * deletion primitive the worker uses.
 *
 * @return the number of top-level entries removed
 */
internal fun deleteStaleEntries(dir: File, cutoffMs: Long): Int =
    dir.listFiles().orEmpty()
        .filter { it.lastModified() < cutoffMs }
        .count { it.deleteRecursively() }
