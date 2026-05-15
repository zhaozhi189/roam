pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 改为 PREFER_SETTINGS 允许 ~/.gradle/init.gradle.kts 的阿里云镜像注入(国内加速)
    // 默认 FAIL_ON_PROJECT_REPOS 会跟 init script 冲突
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "roam"
include(":app")
