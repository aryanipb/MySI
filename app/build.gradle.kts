plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// An unset CI secret arrives as an empty string, so blank means "not set" here.
val reportUrl: String =
    System.getenv("MYSI_REPORT_URL")?.takeIf { it.isNotBlank() }
        ?: (findProperty("mysi.reportUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: ""

android {
    namespace = "com.mysi.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mysi.agent"
        minSdk = 34
        // Google Play requires API 36 for new apps and updates from 31 August 2026.
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.1"

        // Google Form pre-filled link that receives in-app AI content reports (Play's AI-Generated
        // Content policy), from gradle.properties or MYSI_REPORT_URL; release-signed builds fail without it.
        buildConfigField("String", "REPORT_URL", "\"${reportUrl.replace("\"", "")}\"")
        // Linked inside the app, as Google Play's User Data policy requires.
        buildConfigField("String", "PRIVACY_URL", "\"https://github.com/aryanipb/MySI/blob/main/PRIVACY.md\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // CI passes the release key through environment variables; local builds fall back to debug.
    val releaseKeystore = System.getenv("MYSI_KEYSTORE_PATH")
    // Release-signed builds must ship working AI content reports (see Reports.kt for the link format).
    val reportLink = Regex("""^https://docs\.google\.com/forms/.+/viewform\?.*""")
    if (releaseKeystore != null &&
        !(reportLink.matches(reportUrl) && "=category" in reportUrl && "=reply" in reportUrl))
        throw GradleException(
            "MYSI_REPORT_URL must be the Google Form pre-filled link with fields category and reply: " +
                "Play requires in-app AI content reports."
        )
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
            // R8 shrinks, optimises and obfuscates the release; app/proguard-rules.pro lists what
            // native code and reflection need kept, and why. mapping.txt (for de-obfuscating Play
            // crash reports) is written to app/build/outputs/mapping/release/.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Puts native symbol tables in the app bundle for Play's crash reports. Today every
            // shipped .so (LiteRT-LM, MediaPipe, CameraX) is a prebuilt that its publisher already
            // stripped (no .symtab or debug sections), so AGP finds nothing to extract and the
            // bundle carries no BUNDLE-METADATA/com.android.tools.build.debugsymbols; Play shows
            // native frames as offsets plus the libraries' exported (.dynsym) names. Kept so any
            // native library that does carry symbols is covered without another change.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // LiteRT-LM ships only arm64-v8a and x86_64. MediaPipe also ships 32-bit libraries;
            // leaving them out keeps the app bundle from being offered to 32-bit devices that
            // could never load the model runtime.
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**")
        }
    }
    // MediaPipe memory-maps its bundled .tflite models, which only works for uncompressed assets.
    androidResources {
        noCompress += "tflite"
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
    // PDF text extraction for the Documents and Knowledge base skills (Apache-2.0, pure Java).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // BouncyCastle (about 9 MB) is only used by PdfBox's public-key security handler, to open
        // certificate-locked PDFs, which PdfSource refuses with a clear message instead. Password
        // and owner-password PDFs decrypt with the platform's own crypto (PdfSourceTest).
        exclude(group = "org.bouncycastle")
    }

    // Senses (all on-device). MediaPipe Tasks (Apache-2.0): object detection and sound
    // classification with bundled models. 0.10.35 rather than the newer 1.x line: 1.x pins
    // litertlm-android 0.18.0 and adds a separate 15-19 MB audio JNI library (speech synthesis and
    // transcription) that MySI does not use. MediaPipe's usage-statistics upload is switched off in
    // AndroidManifest.xml.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.google.mediapipe:tasks-audio:0.10.35")
    // CameraX (Apache-2.0) for the live vision screen.
    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    testImplementation("junit:junit:4.13.2")
}
