package dev.readthat.compose

import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.readthat.client.IosReadThatGraph
import dev.readthat.deeplink.DeepLinkInbox
import dev.readthat.navigation.PlatformBackGestureBridge
import platform.UIKit.UIViewController

/** SwiftUI/UIKit entrypoint. The controller owns one KMP graph for its lifetime. */
fun MainViewController(
    baseUrl: String,
    appVersion: String,
    demoUsername: String = "",
    demoPassword: String = "",
    allowLocalDevelopmentHttp: Boolean = false,
    buildType: String = "release",
    deepLinks: DeepLinkInbox = DeepLinkInbox(),
    backGestures: PlatformBackGestureBridge = PlatformBackGestureBridge(),
    initialNavigationState: String = "",
    onNavigationStateChanged: (String) -> Unit = {},
): UIViewController {
    val graph = IosReadThatGraph(
        baseUrl,
        appVersion,
        demoUsername,
        demoPassword,
        allowLocalDevelopmentHttp,
        buildType,
    )
    return MainViewController(
        graph = graph,
        deepLinks = deepLinks,
        backGestures = backGestures,
        initialNavigationState = initialNavigationState,
        onNavigationStateChanged = onNavigationStateChanged,
    )
}

/** Dependency-injected entrypoint used by Swift so UI and BGProcessingTask share one graph. */
fun MainViewController(
    graph: IosReadThatGraph,
    deepLinks: DeepLinkInbox = DeepLinkInbox(),
    backGestures: PlatformBackGestureBridge = PlatformBackGestureBridge(),
    onCreationQueued: () -> Unit = {},
    onCommunityVisitQueued: () -> Unit = {},
    onCommunityMembershipQueued: () -> Unit = {},
    initialNavigationState: String = "",
    onNavigationStateChanged: (String) -> Unit = {},
): UIViewController = ComposeUIViewController {
        val readThatViewModel = viewModel {
            graph.createViewModel(
                onCreationQueued = { onCreationQueued() },
                onCommunityVisitQueued = { onCommunityVisitQueued() },
                onCommunityMembershipQueued = { onCommunityMembershipQueued() },
                initialNavigationState = initialNavigationState,
                onNavigationStateChanged = onNavigationStateChanged,
            )
        }
        ReadThatApp(readThatViewModel, deepLinks, backGestures)
}
