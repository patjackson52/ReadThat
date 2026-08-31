import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.readthat.sharing"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies { implementation(project(":core:network")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
