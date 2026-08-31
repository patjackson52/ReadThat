import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.readthat.media.acquisition"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies { api(project(":core:model")) }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation(libs.androidx.core) }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
        }
    }
}
