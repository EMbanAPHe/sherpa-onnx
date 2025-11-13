pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ADD THIS:
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SherpaOnnxTtsEngine"
include(":app")
