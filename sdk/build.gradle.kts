plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
    id("maven-publish")
}

android {
    namespace = "com.openwearables.health.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

repositories {
    google()
    mavenCentral()
}

// The Samsung Health Data SDK is proprietary and is not part of this repository.
// Apps that use Samsung Health add the AAR they download from Samsung; the SDK only
// compiles against it. Maintainers can put the real AAR in samsung-sdk/ (git-ignored)
// to build and test against it; otherwise :samsung-health-stub is used.
val samsungSdkAar = rootProject.file("samsung-sdk")
    .listFiles { file -> file.extension == "aar" }
    ?.firstOrNull()

dependencies {
    // Samsung Health Data SDK (compile-only, supplied by the app at runtime)
    if (samsungSdkAar != null) {
        compileOnly(files(samsungSdkAar))
        testImplementation(files(samsungSdkAar))
    } else {
        compileOnly(project(":samsung-health-stub"))
        testImplementation(project(":samsung-health-stub"))
    }

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Lifecycle for detecting app foreground/background
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.7.0")

    // Security for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Kotlin Serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

val publishVersion = (findProperty("localPublishVersion") as String?) ?: "0.13.3"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.openwearables.health"
                artifactId = "sdk"
                version = publishVersion

                pom {
                    name.set("Open Wearables Health SDK")
                    description.set("Android SDK for reading and syncing health data from Samsung Health and Health Connect")
                    url.set("https://github.com/the-momentum/open_wearables_android_sdk")
                }
            }
        }

        repositories {
            maven {
                name = "mavenLocal"
                url = uri("${System.getProperty("user.home")}/.m2/repository")
            }
        }
    }
}

// The stub has no Samsung behavior to test, so this test only builds against the real AAR.
if (samsungSdkAar == null) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
        .matching { it.name.contains("UnitTest") }
        .configureEach { exclude("**/SamsungActiveEnergyAggregateTest.kt") }
}

// Publish the SDK to the Flutter plugin's bundled repo
val flutterRepoDir = providers.gradleProperty("flutterPluginRepo").orNull

if (flutterRepoDir != null) {
    afterEvaluate {
        publishing {
            repositories {
                maven {
                    name = "flutterPlugin"
                    url = uri(flutterRepoDir)
                }
            }
        }
    }
}
