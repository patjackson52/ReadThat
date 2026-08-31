import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "dev.readthat.feed.ui.shared"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:design"))
            api(project(":core:image-ui"))
            implementation(project(":core:media-ui"))
            implementation(project(":core:observability"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(compose.ui)
            implementation(libs.paging.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
