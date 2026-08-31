plugins { id("readthat.android.library") }

android { namespace = "dev.readthat.core.post" }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:observability"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.android)
    implementation(libs.room3.runtime)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room3.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
