package dev.readthat.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.readthat.client.AndroidReadThatGraph
import dev.readthat.client.ReadThatViewModel
import dev.readthat.client.SharedCreationOutcome
import dev.readthat.deeplink.DeepLinkInbox

/**
 * Thin Android host for the same graph and UI exported to iOS.
 *
 * The graph is process-scoped so configuration changes do not create competing databases,
 * telemetry exporters, byte caches, or HTTP clients. [ReadThatViewModel] remains Activity-scoped
 * and therefore retains the normal Android lifecycle semantics.
 */
@Composable
fun AndroidReadThatApp(
    context: Context,
    baseUrl: String,
    appVersion: String,
    demoUsername: String = "",
    demoPassword: String = "",
    buildType: String = "release",
    deepLinks: DeepLinkInbox = DeepLinkInbox(),
    onCreationQueued: (SharedCreationOutcome) -> Unit = {},
    onCommunityVisitQueued: (String) -> Unit = {},
    onCommunityMembershipQueued: (String) -> Unit = {},
) {
    val applicationContext = context.applicationContext
    val configuration = remember(
        applicationContext,
        baseUrl,
        appVersion,
        demoUsername,
        demoPassword,
        buildType,
    ) {
        AndroidGraphConfiguration(
            baseUrl = baseUrl,
            appVersion = appVersion,
            demoUsername = demoUsername,
            demoPassword = demoPassword,
            buildType = buildType,
        )
    }
    val graph = remember(applicationContext, configuration) {
        AndroidReadThatGraphRegistry.get(applicationContext, configuration)
    }
    val readThatViewModel: ReadThatViewModel = viewModel {
        val savedStateHandle = createSavedStateHandle()
        graph.createViewModel(
            onCreationQueued = onCreationQueued,
            onCommunityVisitQueued = onCommunityVisitQueued,
            onCommunityMembershipQueued = onCommunityMembershipQueued,
            initialNavigationState = savedStateHandle[NAVIGATION_STATE_KEY],
            onNavigationStateChanged = { savedStateHandle[NAVIGATION_STATE_KEY] = it },
        )
    }
    ReadThatApp(readThatViewModel, deepLinks)
}

private const val NAVIGATION_STATE_KEY = "readthat.shared.navigation.v1"

private data class AndroidGraphConfiguration(
    val baseUrl: String,
    val appVersion: String,
    val demoUsername: String,
    val demoPassword: String,
    val buildType: String,
)

private object AndroidReadThatGraphRegistry {
    private val lock = Any()
    private var installed: Pair<AndroidGraphConfiguration, AndroidReadThatGraph>? = null

    fun get(context: Context, configuration: AndroidGraphConfiguration): AndroidReadThatGraph =
        synchronized(lock) {
            installed?.takeIf { it.first == configuration }?.second
                ?: AndroidReadThatGraph(
                    context = context,
                    baseUrl = configuration.baseUrl,
                    appVersion = configuration.appVersion,
                    demoUsername = configuration.demoUsername,
                    demoPassword = configuration.demoPassword,
                    buildType = configuration.buildType,
                ).also { graph -> installed = configuration to graph }
        }
}
