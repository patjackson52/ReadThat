plugins {
    id("readthat.android.application")
    id("readthat.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readthat"
    defaultConfig {
        applicationId = "dev.readthat"
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiBaseUrl = providers.gradleProperty("READTHAT_API_BASE_URL")
            .orElse("")
            .get()
        val demoUsername = providers.gradleProperty("READTHAT_DEMO_USERNAME").orElse("").get()
        val demoPassword = providers.gradleProperty("READTHAT_DEMO_PASSWORD").orElse("").get()
        val useSharedApp = providers.gradleProperty("READTHAT_USE_SHARED_APP")
            .orElse("true")
            .map { raw ->
                raw.toBooleanStrictOrNull()
                    ?: error("READTHAT_USE_SHARED_APP must be exactly 'true' or 'false'")
            }
            .get()
        fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        buildConfigField("String", "READTHAT_API_BASE_URL", quoted(apiBaseUrl.trimEnd('/')))
        buildConfigField("String", "READTHAT_DEMO_USERNAME", quoted(demoUsername))
        buildConfigField("String", "READTHAT_DEMO_PASSWORD", quoted(demoPassword))
        buildConfigField("boolean", "READTHAT_USE_SHARED_APP", useSharedApp.toString())
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            // Sideload distribution still benefits from the normal production optimizer and
            // resource pruning. Dependencies contribute their own consumer keep rules; the app
            // file is intentionally limited to ReadThat-specific exceptions.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

}

dependencies {
    // 2026.x BOMs require AGP 9.1+ and compileSdk 37.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(project(":feature:comments"))
    implementation(project(":core:model"))
    implementation(project(":core:client"))
    implementation(project(":core:observability"))
    implementation(project(":core:data"))
    implementation(project(":core:post"))
    implementation(project(":core:deeplink"))
    implementation(project(":core:navigation"))
    implementation(project(":core:image-ui"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:media-ui"))
    implementation(project(":core:media-acquisition"))
    implementation(project(":core:media-acquisition-ui"))
    implementation(project(":core:sharing"))
    implementation(project(":core:sharing-ui"))
    implementation(project(":core:ui"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:feed-ui"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:search"))
    implementation(project(":feature:communities"))
    implementation(project(":feature:community-detail"))
    implementation(project(":feature:mediafeed"))
    implementation(project(":feature:ad-ui"))
    implementation(project(":feature:shell-ui"))
    implementation(project(":feature:creation-ui"))
    implementation(project(":feature:settings-ui"))
    implementation(project(":feature:community-ui"))
    implementation(project(":feature:auth-ui"))
    implementation(project(":composeApp"))
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.compose.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.coil.network.core)

    // Paging 3 — PagingSource comes from Room, RemoteMediator fills it.
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.metrics.performance)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.media3.common)
    androidTestImplementation(libs.media3.datasource)
}
