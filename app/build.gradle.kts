plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.kogler.a7iiiconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kogler.a7iiiconnect"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.3"
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
            isMinifyEnabled = false
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
}
