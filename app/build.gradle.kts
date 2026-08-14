plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.evan.lazlo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evan.lazlo"
        minSdk = 26 // AICore / on-device GenAI and adaptive icons both want 26+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // -- core --
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // -- compose --
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // -- ai backends --
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    // AICore / on-device Gemini Nano client — kept as a compile-time
    // placeholder dependency; swap for the shipping artifact coordinate
    // once it's out of restricted release.
    // implementation("com.google.ai.edge.aicore:aicore:<version>")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // -- browser engine --
    implementation("org.mozilla.geckoview:geckoview:130.0") // TODO: split into a Play Feature Delivery module

    // -- proxy / inspector --
    implementation("io.netty:netty-all:4.1.110.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
