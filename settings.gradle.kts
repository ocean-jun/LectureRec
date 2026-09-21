pluginManagement {
    repositories {
        // 显式指向 Google Maven 的后端地址（maven.google.com 在构建环境不可达）
        maven("https://dl.google.com/dl/android/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://dl.google.com/dl/android/maven2/")
        mavenCentral()
    }
}

rootProject.name = "LectureRec"
include(":app")
