plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: prefer a keystore.properties file, then CI env vars,
// finally fall back to whatever file already sits in the repo root.
val signStoreFile = System.getenv("KEYSTORE_PATH")
    ?: rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f ->
            val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
            file(p.getProperty("storeFile"))
        }
    ?: file("release.keystore")
val signStorePass = System.getenv("KEYSTORE_PASSWORD") ?: "filebridge2026"
val signKeyAlias = System.getenv("KEY_ALIAS") ?: "filebridge"
val signKeyPass = System.getenv("KEY_PASSWORD") ?: "filebridge2026"

android {
    namespace = "com.filebridge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.filebridge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = signStoreFile
            storePassword = signStorePass
            keyAlias = signKeyAlias
            keyPassword = signKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signStoreFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.bcprov)
}