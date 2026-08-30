plugins {
    id("readthat.android.application")
    id("readthat.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readthat"
    defaultConfig {
        applicationId = "dev.readthat"
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiBaseUrl = providers.gradleProperty("READTHAT_API_BASE_URL")
            .orElse("")
            .get()
        val demoUsername = providers.gradleProperty("READTHAT_DEMO_USERNAME").orElse("").get()
        val demoPassword = providers.gradleProperty("READTHAT_DEMO_PASSWORD").orElse("").get()
        fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        buildConfigField("String", "READTHAT_API_BASE_URL", quoted(apiBaseUrl.trimEnd('/')))
        buildConfigField("String", "READTHAT_DEMO_USERNAME", quoted(demoUsername))
        buildConfigField("String", "READTHAT_DEMO_PASSWORD", quoted(demoPassword))
    }

    buildFeatures { buildConfig = true }

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
    implementation(project(":core:observability"))
    implementation(project(":core:data"))
    implementation(project(":core:post"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:search"))
    implementation(project(":feature:communities"))
    implementation(project(":feature:community-detail"))
    implementation(project(":feature:mediafeed"))
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
