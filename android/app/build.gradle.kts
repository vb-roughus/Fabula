plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// CI (GitHub Actions) passes -PversionCode / -PversionName so every merge to
// main produces a strictly increasing versionCode without touching this file.
// Local builds fall back to the dev defaults below.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("versionName") as String?

// Release signing is driven entirely by environment variables so the keystore
// never lives in the repo: FABULA_KEYSTORE (path to .jks), FABULA_KEYSTORE_PASSWORD,
// FABULA_KEY_ALIAS, FABULA_KEY_PASSWORD. Without them, release builds fall back
// to the debug key so local `assembleRelease` keeps working (not update-compatible
// with CI builds -- CI is the source of truth for distributed APKs).
val releaseKeystorePath: String? = System.getenv("FABULA_KEYSTORE")

android {
    namespace = "app.fabula"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.fabula"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0-dev"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("FABULA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FABULA_KEY_ALIAS")
                keyPassword = System.getenv("FABULA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
