package dev.readthat

import android.app.Application
import android.app.ActivityManager
import android.os.StatFs
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.NetworkFetcher
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatProductAnalyticsConfiguration
import dev.readthat.client.AndroidReadThatProductAnalyticsRegistry
import dev.readthat.image.ui.clearPlatformImageMemoryCache
import dev.readthat.networking.UnifiedCoilNetworkClient
import dev.readthat.networking.UnifiedTransport
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.observability.AndroidPerformanceRecorder
import dev.readthat.observability.AndroidPerformanceSession
import dev.readthat.observability.ProductAnalyticsUploadScheduler
import java.util.concurrent.atomic.AtomicBoolean
import okio.Path.Companion.toOkioPath

class ReadThatApplication : Application(), SingletonImageLoader.Factory {
    private val processHomeTimer = performanceTimer()
    private val firstActivity = AtomicBoolean(true)

    fun newPerformanceSession(): AndroidPerformanceSession {
        val cold = firstActivity.getAndSet(false)
        return AndroidPerformanceSession(
            homeTimer = if (cold) processHomeTimer else performanceTimer(),
            startType = if (cold) "cold" else "warm",
        )
    }

    override fun onCreate() {
        super.onCreate()
        UnifiedTransport.initialize(
            this,
            setOf(
                BuildConfig.READTHAT_API_BASE_URL,
                // The Images delivery host is stable across this account's media.
                // A QUIC hint avoids the first-request HTTP/2 discovery round trip.
                "https://imagedelivery.net",
            ),
        )
        PerformanceTelemetry.install(AndroidPerformanceRecorder(this))
        val productAnalytics = AndroidReadThatProductAnalyticsRegistry.get(
            this,
            AndroidReadThatProductAnalyticsConfiguration(
                client = AndroidReadThatClientConfiguration(
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                ),
                buildType = BuildConfig.BUILD_TYPE,
            ),
        )
        ProductAnalyticsUploadScheduler.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Shared Compose owns its own lifecycle bridge; the mature shell needs this
                // adapter until its root navigation host is migrated.
                if (!BuildConfig.READTHAT_USE_SHARED_APP) productAnalytics.onForeground()
                VideoPlaybackCoordinator.setAppForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!BuildConfig.READTHAT_USE_SHARED_APP) productAnalytics.onBackground()
                VideoPlaybackCoordinator.setAppForeground(false)
            }
        })
        FeedSyncScheduler.initialize(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) clearPlatformImageMemoryCache()
        // UI_HIDDEN can be delivered during the brief no-Activity window of a
        // configuration change. Releasing the process player there races the
        // replacement PlayerView and leaves its surface black until the next
        // foreground transition. ProcessLifecycle already pauses hidden UI;
        // release the decoder only for actual background/critical pressure.
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            VideoPlaybackCoordinator.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearPlatformImageMemoryCache()
        VideoPlaybackCoordinator.trimMemory()
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                val activity = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                MemoryCache.Builder()
                    .maxSizePercent(this, if (activity.isLowRamDevice) 0.12 else 0.20)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image-cache").toOkioPath())
                    .maxSizeBytes(imageCacheBytes())
                    .build()
            }
            .components {
                add(NetworkFetcher.Factory(networkClient = { UnifiedCoilNetworkClient(this@ReadThatApplication) }))
            }
            .build()

    private fun imageCacheBytes(): Long {
        val activity = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val target = when {
            activity.isLowRamDevice -> 64L * MIB
            activity.memoryClass >= 512 -> 256L * MIB
            else -> 128L * MIB
        }
        val available = StatFs(cacheDir.absolutePath).availableBytes
        // Never reserve the old 16 MiB floor when storage is critically low; the cache remains
        // useful at 1 MiB while honoring the device's current free-space signal.
        return minOf(target, (available / 50).coerceAtLeast(1L * MIB))
    }

    private companion object { const val MIB = 1024 * 1024 }
}
