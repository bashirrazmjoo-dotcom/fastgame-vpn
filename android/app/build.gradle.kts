plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionCode = 1
val appVersionName = "1.0.0"
val apiBaseUrl: String = providers.gradleProperty("fastgame.apiBaseUrl").get()

// Release signing comes from the environment (CI secrets); never commit keystores.
val keystorePath: String? = System.getenv("FASTGAME_KEYSTORE_PATH")

android {
    namespace = "com.fastgame.vpn"
    compileSdk = 37

    defaultConfig {
        // The application id can never change after the first upload to Google Play.
        applicationId = "com.fastgame.vpn"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("int", "APP_VERSION_CODE", appVersionCode.toString())
        // Short commit id (set by CI) so it is easy to tell which build is installed.
        buildConfigField("String", "BUILD_ID", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "dev"}\"")
        // Backup route straight to the server (used when the domain cannot be reached, e.g. DNS
        // filtering). Only the server's own certificate is accepted, matched by SHA-256 fingerprint.
        buildConfigField("String", "API_FALLBACK_URL", "\"https://93.126.31.20:24443/\"")
        buildConfigField("String", "API_FALLBACK_HOST", "\"fg.razmjou.com\"")
        buildConfigField(
            "String", "API_FALLBACK_CERT_SHA256",
            "\"435B7DB62EB5E4DFA969EE216F935E1E8FE21F62393D5C38ACA464610796912E\"",
        )
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FASTGAME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FASTGAME_KEY_ALIAS")
                keyPassword = System.getenv("FASTGAME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // the OpenVPN engine executes a binary from the native library directory
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(project(":ics-main")) // OpenVPN engine (GPLv2), see ics-openvpn/FASTGAME-CHANGES.md
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.org)
}
