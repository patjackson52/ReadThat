import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    android {
        namespace = "dev.readthat.core.data"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.room3.runtime)
            api(libs.room3.paging)
            api(libs.paging.common)
            implementation(libs.sqlite.bundled)
            implementation(libs.sqlite.async)
            implementation(libs.coroutines.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit4)
            implementation(libs.coroutines.test)
            implementation(libs.room3.testing)
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Android KMP host tests expose Room/KSP generated sources to lint, but AGP 9 does not yet
// declare that generated-source edge itself. Keep the model deterministic under Gradle 9's
// task validation instead of relying on incidental execution order.
tasks.matching {
    it.name != "kspAndroidHostTest" &&
        it.name.contains("AndroidHostTest") &&
        it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn("kspAndroidHostTest")
}
