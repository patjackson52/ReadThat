import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.readthat.client"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:deeplink"))
            api(project(":core:navigation"))
            api(project(":core:sharing"))
            api(project(":core:model"))
            implementation(project(":core:media-acquisition"))
            api(project(":core:data"))
            api(project(":core:network"))
            api(project(":core:observability"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.room3.runtime)
            implementation(libs.paging.common)
            implementation(libs.lifecycle.viewmodel.mpp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.coroutines.test)
            implementation(libs.room3.testing)
            implementation(libs.paging.testing)
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
        }
    }
}
