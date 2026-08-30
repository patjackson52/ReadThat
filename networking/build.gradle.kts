plugins {
    id("readthat.android.library")
}

android {
    namespace = "dev.readthat.networking"
}

dependencies {
    implementation(project(":core:observability"))
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.network.core)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
}
