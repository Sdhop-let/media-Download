import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
}

// T8.8：构建目录迁到 D 盘——pip 解压数千小文件会触发联想安全软件反勒索启发式
// 把 buildPython 子进程挂死（进程永不退出），D:\AndroidDev 无此问题
layout.buildDirectory.set(File("D:/AndroidDev/build-epdownloader/app"))

android {
    namespace = "com.ep.donwnloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ep.donwnloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
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
    kotlinOptions {
        jvmTarget = "17"
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
        // buildPython：构建机上的 Python（本机 3.14 不受 Chaquopy 支持，专用 3.12.8）
        buildPython = listOf("D:/Python312/python.exe")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")   // P0：凭据 EncryptedSharedPreferences（Keystore 主密钥）
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
