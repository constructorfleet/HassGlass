plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.hassglass.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hassglass.agent"
        minSdk = 26
        // Target 32 keeps the agent on the pre-Android-13 behavior envelope that YodaOS currently tolerates,
        // while compileSdk 35 still lets us build against current APIs and toolchains.
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
}
