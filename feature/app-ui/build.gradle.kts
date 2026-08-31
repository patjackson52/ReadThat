import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "dev.readthat.app.ui"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:client"))
            api(project(":core:deeplink"))
            api(project(":core:model"))
            api(project(":core:navigation"))
            implementation(project(":core:design"))
            implementation(project(":core:data"))
            implementation(project(":core:image-ui"))
            implementation(project(":core:media-ui"))
            implementation(project(":core:media-acquisition"))
            implementation(project(":core:media-acquisition-ui"))
            implementation(project(":core:sharing"))
            implementation(project(":core:sharing-ui"))
            implementation(project(":core:network"))
            implementation(project(":core:observability"))
            implementation(project(":feature:feed-ui"))
            implementation(project(":feature:detail-ui"))
            implementation(project(":feature:mediafeed-ui"))
            implementation(project(":feature:ad-ui"))
            implementation(project(":feature:shell-ui"))
            implementation(project(":feature:search-ui"))
            implementation(project(":feature:profile-ui"))
            implementation(project(":feature:creation-ui"))
            implementation(project(":feature:settings-ui"))
            implementation(project(":feature:community-ui"))
            implementation(project(":feature:auth-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.runtime.compose.mpp)
            implementation(libs.lifecycle.viewmodel.mpp)
            implementation(libs.paging.compose)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation(libs.activity.compose) }
    }
}
