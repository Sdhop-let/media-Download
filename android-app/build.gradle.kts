plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // 16.1.0：_chaquopy 的 OpenSSL/SQLite stub 库改为 16 KB 页对齐（上游 #1324），
    // 修复 Android 16+ 的「应用兼容性」弹窗；同时正式支持 AGP 8.9。
    // 本地镜像需同步：见 android-app/mirror_chaquopy.py（D:/AndroidDev/maven-local）
    id("com.chaquo.python") version "16.1.0" apply false
}
