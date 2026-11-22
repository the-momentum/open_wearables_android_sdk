// Compile-only stand-in for the Samsung Health Data SDK, so :sdk builds (and JitPack
// publishes) without Samsung's proprietary AAR in the repository. It declares only
// the API surface :sdk uses, with JVM signatures matching the real SDK 1.0.0.
// It is never published or packaged: apps supply the real AAR themselves.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openwearables.health.samsungstub"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
