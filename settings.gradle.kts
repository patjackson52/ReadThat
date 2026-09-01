pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Kotlin/JS registers its Node distribution Ivy repository from the plugin.
    // Prefer the centralized catalog while allowing that toolchain repository.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ReadThat"
include(":app")
include(":feature:feed-ui")
include(":feature:detail-ui")
include(":feature:mediafeed-ui")
include(":feature:ad-ui")
include(":feature:shell-ui")
include(":feature:search-ui")
include(":feature:profile-ui")
include(":feature:creation-ui")
include(":feature:settings-ui")
include(":feature:community-ui")
include(":feature:auth-ui")
// KMP application coordinator. This module composes the independently reusable feature UI
// modules; :composeApp is deliberately only the Android/iOS binary host.
include(":feature:app-ui")

// Self-contained, additive. :app does not depend on it; it stands alone with its
// own tests and its own optional demo screen.
include(":flows")

// Platform-neutral contracts, validation, and reducers shared by Android and iOS.
include(":core:model")
project(":core:model").projectDir = file("shared")

// Vendor-neutral performance events and timers shared by Android, iOS, and
// browser clients. Platform exporters live at the application edge.
include(":core:observability")
project(":core:observability").projectDir = file("observability")

// Shared account-scoped Room source of truth for every client feature.
include(":core:data")

// Platform-neutral post/comment URL parsing and delivery. Kept independent from
// navigation and UI so Android and iOS share one contract.
include(":core:deeplink")

// Platform-neutral application destinations and bounded back-stack policy. Hosts
// persist the same validated navigation state through their native state registries.
include(":core:navigation")

// Reusable KMP Compose primitives shared across feature modules.
include(":core:design")

// One process-wide HTTP stack shared by JSON, Coil, and Media3. HttpEngine
// supplies QUIC/HTTP-3 on Android 14+; older releases share one OkHttp pool.
include(":core:network")
project(":core:network").projectDir = file("networking")

// Shared authenticated API, offline-first repositories, state holders and
// synchronization policies consumed by both platform applications.
include(":core:client")

// Thin Android/iOS host and exported iOS framework. Feature rendering is owned by :feature:app-ui.
include(":composeApp")

// Shared Media3 engine: one process player across feed/detail, bounded adjacent
// preloading, manifest-aware disk caching, and network/data-saver policy.
include(":core:media")
project(":core:media").projectDir = file("playback")

// Compose Multiplatform boundary over Media3 on Android and AVPlayer on iOS. Feature UI can
// share playback ownership, HTTPS filtering, prefetch, and observability without hiding the
// platform-native engines behind an application module.
include(":core:media-ui")

// Compose Multiplatform image boundary. Shared code owns HTTPS filtering, stable cache identities,
// retry/loading behavior and bounded prefetch windows; Android retains Coil over UnifiedTransport
// while iOS decodes bytes supplied by the process-scoped shared client.
include(":core:image-ui")

// Shared media-acquisition policy plus narrow platform staging adapters. Native photo/camera
// pickers stay at the host edge, while byte limits, MIME rules, selection bounds, and durable
// app-private staging cannot drift between Android and iOS.
include(":core:media-acquisition")

// Compose Multiplatform presentation boundary for native photo/video pickers and camera capture.
// Feature screens call one launcher contract; Android Activity Results and the Swift PhotosUI
// notification bridge remain target-specific implementations inside the capability module.
include(":core:media-acquisition-ui")

// Typed, platform-neutral share payload policy. Android and iOS only present the native chooser;
// product text/subject/MIME semantics are compiled once.
include(":core:sharing")

// Compose Multiplatform presentation boundary for the system share sheet. Product payload policy
// remains in :core:sharing; Android's chooser and the Swift notification bridge are target actuals.
include(":core:sharing-ui")
