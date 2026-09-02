import java.util.Properties

plugins {
    id("readthat.android.application")
    id("readthat.android.compose")
    id("works.sloop.shipyard.deploy") // dev-build distribution; see .shipyard-deploy.yaml
}

android {
    namespace = "dev.readthat"
    defaultConfig {
        applicationId = "dev.readthat"
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiBaseUrl = providers.gradleProperty("READTHAT_API_BASE_URL")
            .orElse("")
            .get()
        val demoUsername = providers.gradleProperty("READTHAT_DEMO_USERNAME").orElse("").get()
        val demoPassword = providers.gradleProperty("READTHAT_DEMO_PASSWORD").orElse("").get()
        fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        buildConfigField("String", "READTHAT_API_BASE_URL", quoted(apiBaseUrl.trimEnd('/')))
        buildConfigField("String", "READTHAT_DEMO_USERNAME", quoted(demoUsername))
        buildConfigField("String", "READTHAT_DEMO_PASSWORD", quoted(demoPassword))
    }

    buildFeatures { buildConfig = true }

    // Shipyard Deploy shared DEVELOPMENT signing identity (non-production sample key).
    // Debug builds carry the ".dev" application id and this signer so dev builds can
    // update in place on enrolled devices. Never used for release.
    val shipyardSigningProps = file(
        (findProperty("shipyardDevSigningProps") as String?)
            ?: "${System.getProperty("user.home")}/workspace/shipyard-deploy/infra/dev-signing/sample-dev.properties",
    )
    if (shipyardSigningProps.isFile) {
        val props = Properties()
        shipyardSigningProps.inputStream().use { props.load(it) }
        signingConfigs.create("shipyardDev") {
            storeFile = shipyardSigningProps.parentFile.resolve(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }


    buildTypes {
        debug {
            // Shipyard Deploy: distinct dev application id + shared dev signer.
            applicationIdSuffix = ".dev"
            signingConfigs.findByName("shipyardDev")?.let { signingConfig = it }
        }
        release {
            // Sideload distribution still benefits from the normal production optimizer and
            // resource pruning. Dependencies contribute their own consumer keep rules; the app
            // file is intentionally limited to ReadThat-specific exceptions.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

}

dependencies {
    implementation(libs.activity.compose)
    implementation(project(":composeApp"))
    implementation(project(":core:model"))
    implementation(project(":core:client"))
    implementation(project(":core:observability"))
    implementation(project(":core:data"))
    implementation(project(":core:deeplink"))
    implementation(project(":core:image-ui"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:media-acquisition"))
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.singleton)
    implementation(libs.coil.core)
    implementation(libs.coil.network.core)
    implementation(libs.work.runtime)
    implementation(libs.metrics.performance)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.media3.common)
    androidTestImplementation(libs.media3.datasource)
}
