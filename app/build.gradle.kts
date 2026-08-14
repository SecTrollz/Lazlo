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
        minSdk = 31 // the AICore client library itself requires API 31+
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

    packaging {
        resources {
            // The three org.bouncycastle:*-jdk18on jars are multi-release
            // and all carry an identical OSGi manifest fragment; merging
            // fails on the duplicate unless it's excluded explicitly.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // -- ai backends --
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // -- browser engine --
    // GeckoView ships date-stamped versions from Mozilla's own Maven repo
    // (declared in settings.gradle.kts) rather than plain semver.
    implementation("org.mozilla.geckoview:geckoview:130.0.20240913135723") // TODO: split into a Play Feature Delivery module

    // -- proxy / inspector --
    // No Netty: the packet pump (proxy/net/TcpIpStack.kt) is a small
    // hand-written IPv4/TCP codec, and TLS termination bridges through a
    // real loopback SSLSocket/SSLServerSocket pair rather than a Netty
    // pipeline — see ARCHITECTURE.md for why.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
