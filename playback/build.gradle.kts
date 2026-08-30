plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
}

android {
    namespace = "dev.readthat.playback"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:observability"))
    implementation(project(":core:network"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    testImplementation(libs.junit4)
}
