plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.shell"
        minSdk = 26
        // Kept at 35: matches installed build-tools/AGP pair; runs fine on the
        // Android 16 (API 36) Kylin target.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Extract libnode_exec.so to nativeLibraryDir on install:
            // required because the app exec()s the Node binary from there
            // (app-data dirs are no-exec for targetSdk 29+).
            useLegacyPackaging = true
        }
        resources {
            // Do not double-compress the runtime zip asset.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ProxyController (PROXY_OVERRIDE) lives here, not in the framework SDK;
    // the shell needs it to keep the WebView off the fleet's system-wide
    // http_proxy (127.0.0.1:7890 -> tunneled host clash) for its loopback UI.
    implementation("androidx.webkit:webkit:1.12.1")
}
