import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
}

// T8.8：本机 Windows 构建目录迁到 D 盘——pip 解压数千小文件可能触发安全软件反勒索检测。
// CI / 其他开发机通过 EP_BUILD_DIR 覆盖；Linux 默认放项目目录的 build-epdownloader/app。
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val defaultBuildDir = if (isWindows) "D:/AndroidDev/build-epdownloader/app"
    else "${rootProject.projectDir}/build-epdownloader/app"
layout.buildDirectory.set(File(System.getenv("EP_BUILD_DIR") ?: defaultBuildDir))

android {
    namespace = "com.ep.donwnloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ep.donwnloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        ndk {
            // Python 解释器是原生组件：真机 arm64 + 模拟器 x86_64（无需安装 NDK）
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 个人项目：release 通道复用 debug 签名，保证与已安装版本签名一致、可直接覆盖安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin 2.4：kotlinOptions DSL 已移除，统一 compilerOptions
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "META-INF/*"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // 构建机 Python：Chaquopy 需支持的 3.12；CI 通过 CHAQUOPY_BUILD_PYTHON 显式传 setup-python 路径。
        // 本机 Windows 默认专用 Python 3.12，其他系统默认 python3。
        buildPython = listOf(System.getenv("CHAQUOPY_BUILD_PYTHON")?.takeIf { it.isNotBlank() }
            ?: if (isWindows) "D:/Python312/python.exe" else "python3")
        // T8.8 注：不使用 pip {} 配置——构建期 pip 解压数千文件会触发联想安全软件
        // 反勒索启发式把 pip 子进程挂死。yt-dlp 2026.8.19 + PySocks 1.7.1（纯 Python）
        // 已直接放进 src/main/python/，Chaquopy 作为 app Python 源码打包，无需 pip。
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // M11：液态玻璃真折射框架（Kyant0/AndroidLiquidGlass，AGSL vibrancy/blur/lens）
    implementation(libs.kyant.backdrop)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")   // P0：凭据 EncryptedSharedPreferences（Keystore 主密钥）
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
