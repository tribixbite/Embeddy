package app.embeddy

import android.app.Application
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.embeddy.util.CleanupWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import timber.log.Timber
import java.util.concurrent.TimeUnit

class EmbeddyApp : Application(), ImageLoaderFactory {
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

    /**
     * Single app-wide Coil loader shared by every AsyncImage (per-composition
     * `ImageLoader.Builder` calls each carried their own memory cache and were
     * never shut down).
     *
     * [ImageDecoderDecoder] is `@RequiresApi(28)`; composing it on API 26-27
     * (minSdk 26) made every animated preview fail to render. Pre-28 falls back
     * to [GifDecoder], which animates GIFs; animated WebP has no pre-28 platform
     * decoder and renders its first frame statically there.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
}
