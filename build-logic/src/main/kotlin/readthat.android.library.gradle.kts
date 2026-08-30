import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion

plugins { id("com.android.library") }

extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
