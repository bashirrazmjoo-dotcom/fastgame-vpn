import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI numbers every build (100 + run number) so a newer build always installs over an older one.
val appVersionCode = System.getenv("FASTGAME_VERSION_CODE")?.toIntOrNull()?.plus(100) ?: 1
val appVersionName = "1.0.0"
val apiBaseUrl: String = providers.gradleProperty("fastgame.apiBaseUrl").get()

// Release signing comes from the environment (CI secrets); never commit keystores.
val keystorePath: String? = System.getenv("FASTGAME_KEYSTORE_PATH")

android {
    namespace = "com.fastgame.vpn"
    compileSdk = 37
    ndkVersion = "30.0.14904198" // same as :ics-main so native libraries are stripped

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
        // A fixed, public debug key: every CI build is signed the same way, so builds install over each
        // other (no uninstall needed) and ANDROID_ID stays the same. Release builds use the real key below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
            // OpenVPN3 is off by default (we run the classic OpenVPN binary), so its library is not shipped.
            // libosslspeedtest.so must stay: NativeUtils loads it unconditionally.
            excludes += setOf("**/libovpn3.so")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Xray core (MPL-2.0) from github.com/2dust/AndroidLibXrayLite. Downloaded, verified by SHA-256 and trimmed
// to arm64 + armv7 (no geo data, no x86 libraries) so the APK stays small. See docs/XRAY.md.
val xrayVersion = "v26.9.30"
val xraySha256 = "cf71680b776b9ca583747ba652f816b047a655eab875d8951e6141636d88bbd6"
val xrayAar = layout.projectDirectory.file("libs/libv2ray.aar").asFile
val fetchXrayCore = tasks.register("fetchXrayCore") {
    outputs.file(xrayAar)
    onlyIf { !xrayAar.exists() }
    doLast {
        val tmp = File(temporaryDir, "libv2ray-full.aar")
        uri("https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayVersion/libv2ray.aar").toURL().openStream()
            .use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == xraySha256) { "libv2ray.aar checksum mismatch: $digest" }
        xrayAar.parentFile.mkdirs()
        ZipFile(tmp).use { zip ->
            ZipOutputStream(xrayAar.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    val n = entry.name
                    val drop = n.startsWith("assets/") || (n.startsWith("jni/") && !n.startsWith("jni/arm64-v8a/") && !n.startsWith("jni/armeabi-v7a/"))
                    if (drop) continue
                    out.putNextEntry(ZipEntry(n))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchXrayCore) }

dependencies {
    implementation(files(xrayAar).builtBy(fetchXrayCore))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
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
