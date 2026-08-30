plugins { `kotlin-dsl` }

group = "dev.readthat.buildlogic"

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
