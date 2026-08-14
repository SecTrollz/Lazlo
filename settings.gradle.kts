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
        // GeckoView is only published here, not on Google's or Central's Maven.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "Lazlo"
include(":app")
