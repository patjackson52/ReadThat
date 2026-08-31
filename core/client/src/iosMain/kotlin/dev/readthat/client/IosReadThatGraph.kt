package dev.readthat.client

import dev.readthat.data.db.IosDatabaseProvider
import dev.readthat.networking.CachingHttpClient
import dev.readthat.networking.IosFileByteCache
import dev.readthat.networking.IosSharedHttpTransport
import dev.readthat.networking.MemoryByteCache
import dev.readthat.networking.TwoTierByteCache
import dev.readthat.networking.TransportSecurityPolicy
import dev.readthat.observability.FrameHealthAggregator
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.shared.BackgroundFeedMediaPlan
import dev.readthat.shared.backgroundFeedMediaPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class IosReadThatGraph(
    baseUrl: String,
    appVersion: String,
    demoUsername: String = "",
    demoPassword: String = "",
    allowLocalDevelopmentHttp: Boolean = false,
    buildType: String = "release",
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backgroundSyncJob: Job? = null
    val database = IosDatabaseProvider.database
    val client: ReadThatClient
    val telemetry: PerformanceTelemetryExporter
    val productAnalytics: ProductAnalyticsExporter
    private val frameHealth = FrameHealthAggregator()

    init {
        val cache = TwoTierByteCache(
            MemoryByteCache(32L * MIB),
            IosFileByteCache("shared-media", 512L * MIB),
        )
        val securityPolicy = TransportSecurityPolicy(allowLocalDevelopmentHttp)
        val http = CachingHttpClient(
            IosSharedHttpTransport(securityPolicy),
            cache,
            ::platformEpochMillis,
            securityPolicy,
        )
        client = ReadThatClient(
            ClientConfiguration(
                baseUrl,
                appVersion,
                "ios",
                demoUsername,
                demoPassword,
                allowLocalDevelopmentHttp,
            ),
            http,
            database,
            AppleKeychainSessionStore(),
        )
        telemetry = PerformanceTelemetryExporter(client, database, "ios", appVersion, buildType)
            .also(PerformanceTelemetryExporter::install)
        productAnalytics = ProductAnalyticsExporter(
            client,
            database,
            AppleProductAnalyticsStateStore(),
            "ios",
            appVersion,
            buildType,
        ).also(ProductAnalyticsExporter::install)
    }

    fun createViewModel(
        onCreationQueued: (SharedCreationOutcome) -> Unit = {},
        onCommunityVisitQueued: (String) -> Unit = {},
        onCommunityMembershipQueued: (String) -> Unit = {},
        initialNavigationState: String? = null,
        onNavigationStateChanged: (String) -> Unit = {},
    ) = ReadThatViewModel(
        client = client,
        database = database,
        productAnalytics = productAnalytics,
        onCreationQueued = onCreationQueued,
        onCommunityVisitQueued = onCommunityVisitQueued,
        onCommunityMembershipQueued = onCommunityMembershipQueued,
        initialNavigationState = initialNavigationState,
        onNavigationStateChanged = onNavigationStateChanged,
    )

    /**
     * BGProcessingTask bridge for the complete database-first maintenance pass. The same client
     * preserves TLS/HTTP3 sessions across outbox, feed, image and telemetry requests.
     */
    fun runBackgroundMaintenance(completionHandler: (Boolean) -> Unit) {
        launchBackgroundMaintenance(
            refreshHomeFeed = true,
            completionHandler = completionHandler,
        )
    }

    /** Compatibility entry point for hosts that intentionally request only an outbox drain. */
    fun syncPendingMutations(completionHandler: (Boolean) -> Unit) {
        launchBackgroundMaintenance(
            refreshHomeFeed = false,
            completionHandler = completionHandler,
        )
    }

    private fun launchBackgroundMaintenance(
        refreshHomeFeed: Boolean,
        completionHandler: (Boolean) -> Unit,
    ) {
        backgroundSyncJob?.cancel()
        backgroundSyncJob = backgroundScope.launch {
            var succeeded = true
            try {
                client.restoreSession()?.let { account ->
                    val result = SharedBackgroundMaintenance(
                        client = client,
                        database = database,
                        scope = backgroundScope,
                        accountId = account.id,
                    ).run(SharedBackgroundMaintenanceRequest(
                        drainMutations = true,
                        refreshHomeFeed = refreshHomeFeed,
                    ))
                    result.refreshedFeed?.backgroundFeedMediaPlan()?.let { plan ->
                        warmBackgroundImages(plan)
                    }
                }
            } catch (cancelled: CancellationException) {
                succeeded = false
            } catch (_: Throwable) {
                succeeded = false
            }
            try {
                telemetry.flush()
                productAnalytics.flush()
            } catch (cancelled: CancellationException) {
                succeeded = false
            } catch (_: Throwable) {
                succeeded = false
            }
            completionHandler(succeeded)
        }
    }

    /**
     * Keeps photo and first-frame video previews in the shared compressed L1/L2 cache. Requests
     * are deliberately discretionary: feed data may refresh on any connected path, while media
     * warming waits for an unconstrained, non-expensive path and remains bounded by the common
     * plan. Foreground AVPlayer continues to own adaptive HLS source preloading.
     */
    private suspend fun warmBackgroundImages(plan: BackgroundFeedMediaPlan) {
        plan.images.chunked(BACKGROUND_IMAGE_CONCURRENCY).forEach { batch ->
            coroutineScope {
                batch.forEach { image ->
                    launch {
                        try {
                            client.mediaBytes(
                                url = image.url,
                                cacheKey = image.cacheKey,
                                videoPreview = image.videoPreview,
                                allowsExpensiveAccess = false,
                                allowsConstrainedAccess = false,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // The Room refresh is useful even when discretionary warming is denied.
                        }
                    }
                }
            }
        }
    }

    fun cancelPendingMutationSync() {
        backgroundSyncJob?.cancel()
        backgroundSyncJob = null
    }
    suspend fun flushTelemetry() {
        telemetry.flush()
        productAnalytics.flush()
    }

    /** Thin Swift CADisplayLink input; frame policy and export stay in the shared graph. */
    fun recordFramePresentation(durationMillis: Double, frameBudgetMillis: Double) {
        frameHealth.addVsyncInterval(
            surface = PerformanceTelemetry.currentSurface,
            durationMs = durationMillis,
            frameBudgetMs = frameBudgetMillis,
        )?.let(telemetry::record)
    }

    /** Lifecycle boundary for a partial frame batch. Recording remains nonblocking. */
    fun flushFrameHealth() {
        frameHealth.drain().forEach(telemetry::record)
    }

    private companion object {
        const val MIB = 1_048_576L
        const val BACKGROUND_IMAGE_CONCURRENCY = 3
    }
}
