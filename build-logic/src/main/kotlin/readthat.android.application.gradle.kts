import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion

plugins { id("com.android.application") }

extensions.configure<ApplicationExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
