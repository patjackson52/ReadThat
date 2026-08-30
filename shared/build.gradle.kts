import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "dev.readthat.shared"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()
    js(IR) { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
        download = false
    }
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec> {
        download = false
    }
}
