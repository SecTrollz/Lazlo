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
            // Every io.netty:* jar ships its own META-INF/INDEX.LIST
            // (a JAR indexing file with no purpose in an APK); merging
            // fails on the duplicate unless it's excluded explicitly.
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            // The three org.bouncycastle:*-jdk18on jars are multi-release
            // and all carry an identical OSGi manifest fragment.
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
    implementation("androidx.compose.ui:ui-tooling-preview")

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
    // netty-all pulls in desktop-only native epoll/kqueue transport jars
    // that duplicate META-INF/INDEX.LIST and aren't usable on Android
    // anyway (Android uses NIO), so depend on just the modules this
    // component actually needs instead of the "all" aggregate.
    implementation("io.netty:netty-common:4.1.110.Final")
    implementation("io.netty:netty-buffer:4.1.110.Final")
    implementation("io.netty:netty-transport:4.1.110.Final")
    implementation("io.netty:netty-codec:4.1.110.Final")
    implementation("io.netty:netty-codec-http:4.1.110.Final")
    implementation("io.netty:netty-handler:4.1.110.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
