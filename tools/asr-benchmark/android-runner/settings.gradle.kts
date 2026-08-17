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
        // Kover's JVM agent is already frozen in the local plugin-portal
        // cache; offline builds must resolve it from that original source.
        gradlePluginPortal()
    }
}

rootProject.name = "kittyecho-asr-benchmark-runner"
include(":core")
include(":app")
