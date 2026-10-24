plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kogler.a7iiiconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kogler.a7iiiconnect"
        minSdk = 33
        targetSdk = 35
        versionCode = 73
        versionName = "0.44.0"
        // The Rust engine is built for arm64 only; this keeps other libraries' native code out too.
        ndk { abiFilters += "arm64-v8a" }
    }

    // CI decodes the KEYSTORE_B64 secret to a file, so every build has the same signature and
    // installs as an update. Without the secret the build falls back to the debug key.
    val keystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("fixed") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "a7iii"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks the Compose icon library down to the icons actually used (~40 MB -> a few MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Together: phone-to-phone link (Bluetooth to find, Wi-Fi Direct to send).
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Nearby brings an old Fragment along; activity-result APIs need 1.3+ (release lint enforces it).
    implementation("androidx.fragment:fragment:1.8.5")
    // Subject masks: on-device subject segmentation (the model ships with Google Play services).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    // Scanning the desktop app's pairing code, without a camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Smart library: faces and open eyes, what's in a photo, and text in it (models from Play services).
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // People: runs the SFace model (assets/sface_int8.onnx) that tells faces apart.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Screenshot tests: the editor rendered on the JVM (Robolectric + Roborazzi).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.36.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.36.0")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
