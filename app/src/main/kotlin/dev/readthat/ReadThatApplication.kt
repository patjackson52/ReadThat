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
import dev.readthat.networking.UnifiedCoilNetworkClient
import dev.readthat.networking.UnifiedTransport
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.performanceTimer
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.observability.AndroidPerformanceRecorder
import dev.readthat.observability.AndroidPerformanceSession
import dev.readthat.observability.AndroidProductAnalyticsRecorder
import dev.readthat.shared.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class ReadThatApplication : Application(), SingletonImageLoader.Factory {
    private val processHomeTimer = performanceTimer()
    private val firstActivity = AtomicBoolean(true)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        val backend = BackendGraph.repository(this)
        val productAnalytics = AndroidProductAnalyticsRecorder(
            this,
            accountId = { backend.activeAccountId },
            identityReady = { backend.session.value !is SessionState.Restoring },
        )
        ProductAnalytics.install(productAnalytics)
        ProcessLifecycleOwner.get().lifecycle.addObserver(productAnalytics)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                VideoPlaybackCoordinator.setAppForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                VideoPlaybackCoordinator.setAppForeground(false)
            }
        })
        applicationScope.launch {
            backend.session.collect { state ->
                // Restoring is not an identity transition. Waiting avoids
                // rotating a recovered signed-in session into a guest session.
                if (state !is SessionState.Restoring) productAnalytics.identityChanged()
            }
        }
        FeedSyncScheduler.initialize(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN can be delivered during the brief no-Activity window of a
        // configuration change. Releasing the process player there races the
        // replacement PlayerView and leaves its surface black until the next
        // foreground transition. ProcessLifecycle already pauses hidden UI;
        // release the decoder only for actual background/critical pressure.
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            VideoPlaybackCoordinator.trimMemory()
        }
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
