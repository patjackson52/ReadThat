import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "dev.readthat.compose"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()
    listOf(iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "ReadThatShared"
            isStatic = true
            // Keep the Kotlin/Native linker and every object we produce aligned with
            // the host application's supported iOS deployment target.
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=16.0"
            binaryOption("bundleId", "dev.readthat.shared")
            export(project(":core:model"))
            export(project(":core:client"))
            export(project(":core:deeplink"))
            export(project(":core:navigation"))
            export(project(":core:media-acquisition"))
            export(project(":core:sharing"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:app-ui"))
            api(project(":core:client"))
            api(project(":core:deeplink"))
            api(project(":core:model"))
            api(project(":core:navigation"))
            api(project(":core:media-acquisition"))
            api(project(":core:sharing"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.lifecycle.viewmodel.compose.mpp)
        }
        androidMain.dependencies { implementation(libs.activity.compose) }
    }
}
