pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // 可选：本地 miuix 仓库（未配置时走 Maven Central）
        val local = System.getenv("MIUIX_REPO")
        if (!local.isNullOrBlank()) {
            maven {
                url = uri(local)
                content { includeGroupByRegex("top\\.yukonga\\.miuix.*") }
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        val local = System.getenv("MIUIX_REPO")
        if (!local.isNullOrBlank()) {
            maven {
                url = uri(local)
                content { includeGroupByRegex("top\\.yukonga\\.miuix.*") }
            }
        }
    }
}

rootProject.name = "AppKiller"
include(":app")
