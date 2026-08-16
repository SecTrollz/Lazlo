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

    // GeckoView lives in dynamic-features/gecko-engine/ instead of a
    // plain dependency here — see that module's build.gradle.kts and
    // BrowserEngineLoader.kt for why (it's ~30-50MB on its own). The
    // Gradle project's logical name (and therefore this path) is
    // gecko_engine, not gecko-engine — see the rename in
    // settings.gradle.kts for why: Android feature module names can't
    // contain hyphens.
    dynamicFeatures += setOf(":dynamic-features:gecko_engine")

    packaging {
        resources {
            // Every io.netty:* jar ships its own META-INF/INDEX.LIST (a
            // JAR indexing file with no purpose in an APK); merging fails
            // on the duplicate unless it's excluded explicitly.
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
    // ChromiumEngine uses WebSettingsCompat/WebViewFeature to strip
    // WebView's embedded-browser tells from the Client Hints surface —
    // see the doc comment on ChromiumEngine.hardenFingerprint().
    implementation("androidx.webkit:webkit:1.17.0")

    // -- compose --
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Downloadable Google Fonts (LazloTheme's parchment typography) —
    // fetched at runtime through Play services rather than bundled as
    // .ttf assets. Version comes from the compose-bom platform() above,
    // same as every other androidx.compose.ui:* dependency here.
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // -- ai backends --
    // 0.10.35, not the older 0.10.14 this pinned before: the downloadable
    // offline model (LocalModelDownloader) is a Gemma 3 .task bundle, and
    // Gemma 3 support landed in tasks-genai well after 0.10.14 shipped.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // -- browser engine --
    // No GeckoView dependency here on purpose: it lives in the
    // dynamic-features/gecko-engine module (see settings.gradle.kts and
    // that module's build.gradle.kts) and is loaded on demand via
    // Play Feature Delivery — see BrowserEngineLoader.kt.
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")

    // -- proxy / inspector --
    // netty-all pulls in desktop-only native epoll/kqueue transport jars
    // that duplicate META-INF/INDEX.LIST inside an APK and aren't usable
    // on Android anyway (Android uses plain NIO), so depend on the
    // individual modules the embedded proxy (proxy/net/ConnectionRelay.kt)
    // actually needs instead of the "all" aggregate.
    implementation("io.netty:netty-common:4.1.110.Final")
    implementation("io.netty:netty-buffer:4.1.110.Final")
    implementation("io.netty:netty-transport:4.1.110.Final")
    implementation("io.netty:netty-codec:4.1.110.Final")
    implementation("io.netty:netty-handler:4.1.110.Final")
    // Real HTTP/1.x parsing for RewriteEngine (proxy/net/RewriteEngine.kt)
    // — request/response rewrite rules operate on actually-decoded
    // HttpRequest/HttpResponse objects, not regex-on-bytes.
    implementation("io.netty:netty-codec-http:4.1.110.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
