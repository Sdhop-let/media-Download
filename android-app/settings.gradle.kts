pluginManagement {
    repositories {
        maven { url = uri("file:///D:/AndroidDev/maven-local") }  // T8.8：Chaquopy 组件本地镜像（GFW 绕行）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///D:/AndroidDev/maven-local") }  // T8.8：本地镜像最优先
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "EPDownloader"
include(":app")
