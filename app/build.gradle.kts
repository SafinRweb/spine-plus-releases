import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// In Spine+ the key named here is Spine+'s own release key (spine-plus.jks), not Google
// Play's upload key: every Spine+ update must be signed with it, or Android refuses it.
// The upload key for Google Play. There are three ways a release build gets signed:
// - keystore.properties exists (never committed, see .gitignore): signed with the upload key.
// - built with -Punsigned: left unsigned, to be signed by hand with jarsigner, so the
//   key's password never has to be written to a file.
// - otherwise: signed with the debug key, so the build can still be installed and tested.
val uploadKey = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasUploadKey = uploadKey.getProperty("storeFile") != null
val leaveUnsigned = providers.gradleProperty("unsigned").isPresent

android {
    namespace = "app.spine.player"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // Spine+ is its own app: it installs beside Spine and never replaces it. The code
        // keeps Spine's package name (the namespace above), so fixes merge across cleanly.
        applicationId = "app.spine.player.plus"
        // Android 11: the first version with the system prompt that tag editing and
        // deleting rely on (MediaStore.createWriteRequest / createDeleteRequest).
        minSdk = 30
        targetSdk = 36
        // 1.0.0 (code 1) is the build that was uploaded to Google Play on 2026-11-14.
        // 1.1.1 (code 3) is the first Spine+ release published on GitHub, 2026-11-16.
        // 1.2.0 (code 4): the video player, playlists with videos, more stats, the name, dark, 2026-11-17.
        versionCode = 4
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The download engine is about 50 MB for each kind of processor, so the app is
        // built for one: 64-bit ARM, which is every phone that runs Android 11 well.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The engine's programs (Python, ffmpeg, QuickJS) are packed as native libraries and
    // run from where Android unpacks them, so they must be unpacked at install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Python and ffmpeg arrive as zip archives named like libraries; they are not
            // code the build can strip, so it is told to leave them as they are.
            keepDebugSymbols += "**/*.zip.so"
        }
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Signed with the release key when it is here, so a debug build can go over the
            // released app on the same phone, for tests, without uninstalling it first.
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = when {
                hasUploadKey -> signingConfigs.getByName("upload")
                leaveUnsigned -> null
                else -> signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.taglib)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
