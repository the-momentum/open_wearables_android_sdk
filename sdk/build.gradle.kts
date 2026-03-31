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
    maven {
        url = uri("${project.projectDir}/libs/maven")
    }
}

dependencies {
    // Samsung Health Data SDK
    implementation("com.samsung.android.health:data:1.0.0")

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.openwearables.health"
                artifactId = "sdk"
                version = "0.9.0"

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

tasks.register<Copy>("installSamsungSdkToMavenLocal") {
    from("${project.projectDir}/libs/maven/com/samsung/android/health/data")
    into("${System.getProperty("user.home")}/.m2/repository/com/samsung/android/health/data")
}

tasks.matching { it.name.contains("PublicationToMavenLocal") }.configureEach {
    dependsOn("installSamsungSdkToMavenLocal")
}

// Publish SDK + Samsung dependency to the Flutter plugin's bundled repo
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

        tasks.register<Copy>("installSamsungSdkToFlutterPlugin") {
            from("${project.projectDir}/libs/maven/com/samsung/android/health/data")
            into("$flutterRepoDir/com/samsung/android/health/data")
        }

        tasks.matching { it.name.contains("PublicationToFlutterPlugin") }.configureEach {
            dependsOn("installSamsungSdkToFlutterPlugin")
        }
    }
}
