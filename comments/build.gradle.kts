plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.readthat.comments"
    // AGP 9 includes src/main/kotlin and src/test/kotlin by default.
}

dependencies {
    implementation(project(":core:image-ui"))
    implementation(project(":core:media-ui"))
    implementation(project(":core:model"))
    implementation(project(":core:post"))
    implementation(project(":core:observability"))
    implementation(project(":core:client"))
    implementation(project(":core:ui"))
    // Public PostDetailScreen compatibility aliases expose the shared detail contracts.
    api(project(":feature:detail-ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
