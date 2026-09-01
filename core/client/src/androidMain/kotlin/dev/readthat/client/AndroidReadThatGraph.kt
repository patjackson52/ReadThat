package dev.readthat.client

import android.content.Context
import dev.readthat.data.db.AndroidDatabaseProvider
import dev.readthat.data.db.AppDatabase
import dev.readthat.networking.AndroidFileByteCache
import dev.readthat.networking.AndroidSharedHttpTransport
import dev.readthat.networking.CachingHttpClient
import dev.readthat.networking.MemoryByteCache
import dev.readthat.networking.TwoTierByteCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class AndroidReadThatClientConfiguration(
    val baseUrl: String,
    val appVersion: String,
    val demoUsername: String = "",
    val demoPassword: String = "",
)

data class AndroidReadThatProductAnalyticsConfiguration(
    val client: AndroidReadThatClientConfiguration,
    val buildType: String,
)

class AndroidReadThatClientRuntime internal constructor(
    val database: AppDatabase,
    val client: ReadThatClient,
)

/** Process-wide Android owner for Room, byte caches, TLS sessions and pooled HTTP connections. */
object AndroidReadThatClientRegistry {
    private val lock = Any()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed:
        Pair<AndroidReadThatClientConfiguration, AndroidReadThatClientRuntime>? = null

    fun get(
        context: Context,
        configuration: AndroidReadThatClientConfiguration,
    ): AndroidReadThatClientRuntime = synchronized(lock) {
        installed?.takeIf { it.first == configuration }?.second
            ?: create(context.applicationContext, configuration).also { runtime ->
                installed = configuration to runtime
            }
    }

    private fun create(
        context: Context,
        configuration: AndroidReadThatClientConfiguration,
    ): AndroidReadThatClientRuntime {
        val database = AndroidDatabaseProvider.get(context)
        val cache = TwoTierByteCache(
            MemoryByteCache(32L * MIB),
            AndroidFileByteCache(context, "shared-media", 512L * MIB),
        )
        val http = CachingHttpClient(AndroidSharedHttpTransport(context), cache, ::platformEpochMillis)
        val client = ReadThatClient(
            ClientConfiguration(
                configuration.baseUrl,
                configuration.appVersion,
                "android",
                configuration.demoUsername,
                configuration.demoPassword,
            ),
            http,
            database,
            AndroidSecureSessionStore(context),
        )
        processScope.launch {
            // Upgrade failure leaves the legacy keys intact for a later retry and must not make
            // the process-wide networking graph unavailable.
            runCatching { migrateLegacyAndroidSettings(context, database) }
        }
        return AndroidReadThatClientRuntime(database, client)
    }

    private const val MIB = 1_048_576L
}

/** One process exporter means one engagement checkpoint, one Room FIFO and one client. */
object AndroidReadThatProductAnalyticsRegistry {
    private val lock = Any()
    private var installed:
        Pair<AndroidReadThatProductAnalyticsConfiguration, ProductAnalyticsExporter>? = null

    fun get(
        context: Context,
        configuration: AndroidReadThatProductAnalyticsConfiguration,
    ): ProductAnalyticsExporter = synchronized(lock) {
        installed?.takeIf { it.first == configuration }?.second
            ?: AndroidReadThatClientRegistry.get(context, configuration.client).let { runtime ->
                ProductAnalyticsExporter(
                    runtime.client,
                    runtime.database,
                    AndroidProductAnalyticsStateStore(context.applicationContext),
                    "android",
                    configuration.client.appVersion,
                    configuration.buildType,
                ).also(ProductAnalyticsExporter::install)
            }.also { exporter -> installed = configuration to exporter }
    }
}

class AndroidReadThatGraph(
    context: Context,
    baseUrl: String,
    appVersion: String,
    demoUsername: String = "",
    demoPassword: String = "",
    buildType: String = "release",
) {
    private val appContext = context.applicationContext
    val database = AndroidDatabaseProvider.get(appContext)
    val client: ReadThatClient
    val telemetry: PerformanceTelemetryExporter
    val productAnalytics: ProductAnalyticsExporter

    init {
        val clientConfiguration = AndroidReadThatClientConfiguration(
            baseUrl = baseUrl,
            appVersion = appVersion,
            demoUsername = demoUsername,
            demoPassword = demoPassword,
        )
        val runtime = AndroidReadThatClientRegistry.get(appContext, clientConfiguration)
        client = runtime.client
        telemetry = PerformanceTelemetryExporter(client, database, "android", appVersion, buildType)
            .also(PerformanceTelemetryExporter::install)
        productAnalytics = AndroidReadThatProductAnalyticsRegistry.get(
            appContext,
            AndroidReadThatProductAnalyticsConfiguration(clientConfiguration, buildType),
        )
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
}
