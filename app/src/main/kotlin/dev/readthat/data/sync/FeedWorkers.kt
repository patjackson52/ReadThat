package dev.readthat.data.sync

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.readthat.BuildConfig
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatClientRegistry
import dev.readthat.client.OfflineFirstRepository
import dev.readthat.client.SharedBackgroundMaintenance
import dev.readthat.client.SharedBackgroundMaintenanceRequest
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoStartupPrefetcher
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AndroidDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import dev.readthat.shared.BackgroundFeedMediaPlan
import dev.readthat.shared.AppSettings
import dev.readthat.shared.ConnectionKind
import dev.readthat.shared.DeviceTier
import dev.readthat.shared.VideoPolicyResolver
import dev.readthat.shared.backgroundFeedMediaPlan

class FeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AndroidDatabaseProvider.get(applicationContext)
        val account = db.accountDao().active() ?: return Result.success()
        return try {
            val runtime = AndroidReadThatClientRegistry.get(
                applicationContext,
                AndroidReadThatClientConfiguration(
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                ),
            )
            if (runtime.client.restoreSession()?.id != account.id) return Result.success()
            val page = SharedBackgroundMaintenance(
                client = runtime.client,
                database = runtime.database,
                scope = CoroutineScope(currentCoroutineContext()),
                accountId = account.id,
            ).run(
                SharedBackgroundMaintenanceRequest(
                    // Dedicated unique workers retain Android's fine-grained retry/backoff lanes.
                    drainMutations = false,
                    refreshHomeFeed = true,
                ),
            ).refreshedFeed ?: return Result.success()
            // Feed refresh remains useful on every connected network. Posters are small and warm
            // Coil's disk/memory caches for first-pixel scrolling. The common plan now includes
            // still photos too, so a periodic refresh improves the actual offline feed rather than
            // only its video placeholders. Video bytes remain a separate best-effort unmetered
            // step: one video and only its first two seconds.
            try {
                prefetchStartupMedia(db, page.backgroundFeedMediaPlan())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Media warming is optional; a failure must not roll back the
                // already-committed Room refresh or schedule a feed retry.
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private suspend fun prefetchStartupMedia(db: AppDatabase, plan: BackgroundFeedMediaPlan) {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        if (connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return

        plan.images.forEach { image ->
            applicationContext.imageLoader.execute(
                ImageRequest.Builder(applicationContext)
                    .data(image.url)
                    .memoryCacheKey(
                        (if (image.videoPreview) "preview:" else "image:") + image.cacheKey,
                    )
                    .diskCacheKey(image.cacheKey)
                    .build(),
            )
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return

        val settings = db.appSettingsDao().get()?.let {
            AppSettings(
                darkTheme = it.darkTheme,
                compactPosts = it.compactPosts,
                autoplayVideo = it.autoplayVideo,
                autoplayOnMetered = it.autoplayOnMetered,
                reduceDataOnMetered = it.reduceDataOnMetered,
                reduceAnimations = it.reduceAnimations,
                blurMatureMedia = it.blurMatureMedia,
            )
        } ?: AppSettings()
        if (!settings.autoplayVideo) return

        val activity = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val deviceTier = when {
            activity.isLowRamDevice -> DeviceTier.LowMemory
            activity.memoryClass >= 512 -> DeviceTier.HighEnd
            else -> DeviceTier.Standard
        }
        val policy = VideoPolicyResolver.resolve(
            settings = settings,
            connection = ConnectionKind.Unmetered,
            dataSaverEnabled = false,
            deviceTier = deviceTier,
            availableCacheBytes = StatFs(applicationContext.cacheDir.absolutePath).availableBytes,
        )
        VideoStartupPrefetcher.prefetch(
            context = applicationContext,
            sources = plan.videos.map { video ->
                AdaptiveVideoSource(
                    hlsUrl = video.hlsUrl,
                    fallbackUrl = video.fallbackUrl,
                    cacheKey = video.cacheKey,
                )
            },
            cacheBytes = policy.cacheBytes,
        )
    }
}

class VoteOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AndroidDatabaseProvider.get(applicationContext)
        val account = db.accountDao().active() ?: return Result.success()
        return try {
            val runtime = AndroidReadThatClientRegistry.get(
                applicationContext,
                AndroidReadThatClientConfiguration(
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                ),
            )
            if (runtime.client.restoreSession()?.id != account.id) return Result.success()
            OfflineFirstRepository(
                client = runtime.client,
                database = runtime.database,
                scope = CoroutineScope(currentCoroutineContext()),
                accountIdOverride = account.id,
                maintainGlobalState = false,
            ).syncPendingVotes()
            if (runtime.database.feedDao().pendingVotes(account.id).isEmpty()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
