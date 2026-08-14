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
include(":dynamic-features:gecko-engine")
// Android dynamic-feature module names may only contain letters, digits,
// and underscores (no hyphens) — the directory stays gecko-engine to
// match ARCHITECTURE.md/dynamic-features/gecko-engine, but the Gradle
// project's logical name, which AGP uses as the actual feature/split
// name, has to be the underscore form.
project(":dynamic-features:gecko-engine").name = "gecko_engine"
