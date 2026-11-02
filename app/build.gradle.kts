plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mysi.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mysi.agent"
        minSdk = 34
        // 35, not 36: API 36 replaces BODY_SENSORS with Health Connect permissions.
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    // CI passes the release key through environment variables; local builds fall back to debug.
    val releaseKeystore = System.getenv("MYSI_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MYSI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYSI_KEY_ALIAS")
                keyPassword = System.getenv("MYSI_KEY_PASSWORD")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.code.gson:gson:2.14.0")
}
