plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
}

android { namespace = "dev.readthat.profile" }

dependencies {
    implementation(project(":core:model"))

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.core)
}
