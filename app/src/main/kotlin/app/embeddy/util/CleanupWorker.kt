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

        // Clean each cache subdirectory used by engines
        listOf("converted", "temp", "squoosh_out").forEach { dirName ->
            val dir = File(applicationContext.cacheDir, dirName)
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { file ->
                if (file.delete()) deleted++
            }
        }

        Timber.d("CleanupWorker: deleted %d stale cache files", deleted)
        return Result.success()
    }
}
