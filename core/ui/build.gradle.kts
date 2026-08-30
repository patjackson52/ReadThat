plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
}

android {
    namespace = "dev.readthat.core.ui"
}

dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit4)
}
