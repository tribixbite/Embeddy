package app.embeddy

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.embeddy.util.CleanupWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

class EmbeddyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Plant Timber debug tree for logcat output in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Schedule periodic cache cleanup every 24 hours
        val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(
            24, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "embeddy_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest,
        )
    }
}
