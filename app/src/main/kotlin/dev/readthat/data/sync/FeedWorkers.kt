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
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoStartupPrefetcher
import dev.readthat.data.FeedSyncEngine
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.shared.AppSettings
import dev.readthat.shared.ConnectionKind
import dev.readthat.shared.DeviceTier
import dev.readthat.shared.VideoPolicyResolver
import dev.readthat.shared.videoPosterCacheKey

private fun syncEngine(context: Context): FeedSyncEngine {
    val db = AppDatabase.get(context)
    return FeedSyncEngine(
        db = db,
        remote = BackendGraph.feed(context),
        json = Json { ignoreUnknownKeys = true },
    )
}

class FeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val account = db.accountDao().active() ?: return Result.success()
        return try {
            val page = syncEngine(applicationContext).refresh(account.id, CacheScope.HOME_FEED_ID)
            // Feed refresh remains useful on every connected network. Posters are small and warm
            // Coil's disk/memory caches for first-pixel scrolling. Video bytes remain a separate,
            // best-effort unmetered step: one video and only its first two seconds.
            try {
                prefetchStartupMedia(db, page)
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

    private suspend fun prefetchStartupMedia(db: AppDatabase, page: WireFeedPage) {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        if (connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return

        page.startupVideoPosters().forEach { poster ->
            applicationContext.imageLoader.execute(
                ImageRequest.Builder(applicationContext)
                    .data(poster.url)
                    .memoryCacheKey(poster.cacheKey)
                    .diskCacheKey(poster.cacheKey)
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
            sources = page.startupVideoSources(),
            cacheBytes = policy.cacheBytes,
        )
    }
}

internal fun WireFeedPage.startupVideoSources(): List<AdaptiveVideoSource> = groups.mapNotNull { group ->
    group.cells.filterIsInstance<WireCell.Video>().firstOrNull()
        ?.takeIf { it.deliveryStatus == "ready" || it.fallbackUrl != null || it.url != null }
        ?.let { video ->
            AdaptiveVideoSource(
                hlsUrl = video.hlsUrl,
                fallbackUrl = video.fallbackUrl ?: video.url,
                cacheKey = video.cacheKey ?: "post:${group.groupId}",
            )
        }
}.take(VideoStartupPrefetcher.MAX_STARTUP_VIDEOS)

internal data class StartupVideoPoster(val url: String, val cacheKey: String)

internal fun WireFeedPage.startupVideoPosters(): List<StartupVideoPoster> = groups.mapNotNull { group ->
    val video = group.cells.filterIsInstance<WireCell.Video>().firstOrNull() ?: return@mapNotNull null
    val poster = video.posterUrl ?: return@mapNotNull null
    StartupVideoPoster(
        poster,
        videoPosterCacheKey(video.cacheKey ?: "post:${group.groupId}", poster),
    )
}.take(MAX_STARTUP_POSTERS)

private const val MAX_STARTUP_POSTERS = 6

class VoteOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val account = db.accountDao().active() ?: return Result.success()
        return try {
            if (syncEngine(applicationContext).drainVoteOutbox(account.id)) {
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
