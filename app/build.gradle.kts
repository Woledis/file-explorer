plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release 直接复用 debug 签名，无需证书即可编译、安装；需要正式签名时再替换为 keystore。

android {
    namespace = "com.filebridge.app"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.filebridge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // R8 死代码收缩 + 资源压缩,显著加快冷启动并减小 APK
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            // 第三方 jar(MINA/FtpServer/BouncyCastle 等)各自携带相同的 META-INF 资源，
            // 合并时去重,避免 mergeDebugJavaResource 报 "3 files found"。
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
        jniLibs {
            // 压缩原生库(.so 约可压掉一半),显著减小 APK 体积;真机运行时按需解压。
            useLegacyPackaging = true
        }
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
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.ftpserver.core)
    implementation(libs.slf4j.nop)
}

// ---- 编译 Rust 核心为 .so 并放入 jniLibs ----
val rustDir = file("$projectDir/src/main/rust")
val rustJniLibs = file("$projectDir/src/main/jniLibs")
val androidAbis = listOf("arm64-v8a")

fun onPath(tool: String): Boolean {
    val cmdEast = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
    return runCatching {
        ProcessBuilder(cmdEast, tool).redirectErrorStream(true).start().waitFor()
    }.getOrDefault(1) == 0
}

tasks.register("buildRust") {
    group = "build"
    description = "Cross-compiles the Rust core to .so via cargo-ndk"
    onlyIf { onPath("cargo-ndk") }
    doLast {
        val ndkHome = (System.getenv("ANDROID_NDK_HOME")
            ?: (System.getenv("ANDROID_HOME")?.let { "$it/ndk/25.2.9519653" }))
            ?: error("ANDROID_NDK_HOME is not set; install NDK 25.2.9519653 first")
        delete(rustJniLibs)
        exec {
            workingDir = rustDir
            environment["ANDROID_NDK_HOME"] = ndkHome
            commandLine = buildList {
                add("cargo")
                add("ndk")
                for (abi in androidAbis) {
                    add("-t")
                    add(abi)
                }
                add("-o")
                add(rustJniLibs.absolutePath)
                add("build")
                add("--release")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn("buildRust") }