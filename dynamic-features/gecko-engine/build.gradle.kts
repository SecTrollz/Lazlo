plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evan.lazlo.gecko"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":app"))
    // GeckoView ships date-stamped versions from Mozilla's own Maven repo
    // (declared in settings.gradle.kts) rather than plain semver.
    implementation("org.mozilla.geckoview:geckoview:130.0.20240913135723")
}
