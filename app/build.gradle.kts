plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sleqa.wififix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sleqa.wififix"
        minSdk = 26

        // ---------------------------------------------------------------
        // DO NOT RAISE THIS. The entire app depends on it.
        //
        // WifiServiceImpl.setWifiEnabled() rejects third-party callers
        // unless isTargetSdkLessThan(Q) is true. At targetSdk 29+ the call
        // silently returns false and this app becomes a no-op. See README.
        // ---------------------------------------------------------------
        targetSdk = 28

        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Fall back to debug signing so CI always emits an installable APK,
            // even before anyone configures release keystore secrets.
            signingConfig = if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Mandatory. ExpiredTargetSdkVersion is a FATAL lint check and runs as part
    // of lintVitalRelease, which assembleRelease depends on. Without this the
    // release build fails outright because of the deliberate targetSdk 28.
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// Keep the sideloaded release asset easy to identify outside Gradle's default
// output naming. Debug builds retain Gradle's default name.
val releaseApk by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/release"))
    rename { "WifiFix.apk" }
}

// CI is the only place these tests run, so make the results legible there.
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
