plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readthat.search"
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:client"))
    implementation(project(":core:data"))
    implementation(project(":core:image-ui"))
    implementation(project(":core:ui"))
    implementation(project(":feature:search-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    // Retained only by LegacySearchScreen while the mature Android reference remains compiled.
    implementation(libs.coil.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.room3.runtime)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.paging.testing)
    testImplementation(libs.room3.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
