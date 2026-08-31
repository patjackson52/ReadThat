import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.readthat.networking"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project(":core:observability"))
            implementation(libs.coroutines.android)
            implementation(libs.okhttp)
            implementation(libs.coil.network.core)
            implementation(libs.media3.datasource)
            implementation(libs.media3.datasource.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
