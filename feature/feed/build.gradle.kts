plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readthat.feed"
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core:client"))
    implementation(project(":core:model"))
    implementation(project(":core:observability"))
    implementation(project(":core:data"))
    implementation(project(":core:image-ui"))
    implementation(project(":core:post"))
    implementation(project(":core:media"))
    implementation(project(":core:media-ui"))
    implementation(project(":core:ui"))
    implementation(project(":feature:feed-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.room3.runtime)
    implementation(libs.room3.paging)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.paging.common)
    testImplementation(libs.paging.testing)
    testImplementation(libs.room3.testing)
    testImplementation(libs.sqlite.framework)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
