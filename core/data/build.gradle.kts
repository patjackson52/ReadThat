plugins {
    id("readthat.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.readthat.core.data"
}

dependencies {
    // Public database/DAO signatures expose Room and Paging types.
    api(libs.room.runtime)
    api(libs.room.paging)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
