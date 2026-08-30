plugins {
    id("readthat.android.library")
    id("readthat.android.compose")
}

android {
    namespace = "dev.readthat.flows"
    // AGP 9 includes src/main/kotlin and src/test/kotlin by default.

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
