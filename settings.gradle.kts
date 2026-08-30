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
include(":feature:feed")
include(":feature:profile")
include(":feature:search")
include(":feature:communities")
include(":feature:community-detail")
include(":feature:mediafeed")

// Self-contained, additive. :app does not depend on it; it stands alone with its
// own tests and its own optional demo screen.
include(":flows")

// Post-detail screen: recursive comment tree, two-phase load, collapse.
include(":feature:comments")
project(":feature:comments").projectDir = file("comments")

// Platform-neutral contracts, validation, and reducers shared by Android now
// and by the planned iOS/web clients later.
include(":core:model")
project(":core:model").projectDir = file("shared")

// Vendor-neutral performance events and timers shared by Android, iOS, and
// browser clients. Platform exporters live at the application edge.
include(":core:observability")
project(":core:observability").projectDir = file("observability")

// Shared account-scoped Room source of truth. Persistence used by more than
// one feature must not be owned by :feature:feed.
include(":core:data")
include(":core:post")

// Reusable Compose primitives shared across feature modules. Keeping rich-text rendering here
// avoids feature-to-feature dependencies while giving posts and comments one Markdown contract.
include(":core:ui")

// One process-wide HTTP stack shared by JSON, Coil, and Media3. HttpEngine
// supplies QUIC/HTTP-3 on Android 14+; older releases share one OkHttp pool.
include(":core:network")
project(":core:network").projectDir = file("networking")

// Shared Media3 engine: one process player across feed/detail, bounded adjacent
// preloading, manifest-aware disk caching, and network/data-saver policy.
include(":core:media")
project(":core:media").projectDir = file("playback")
