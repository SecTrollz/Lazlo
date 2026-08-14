# Lazlo — Architecture

A minimal, privacy-first Android app combining a swappable AI chat backend
(BYOK API key or on-device model) with a swappable browser engine
(WebView/Chromium or GeckoView/Firefox) and a local traffic inspector for
the app's own network activity. No telemetry, no analytics SDKs, no
first-party backend — the app talks only to (a) the AI endpoint the user
picks and (b) the sites the user browses.

## Scope note on the "MITM proxy"

This is a **self-interception** tool, the same category as mitmproxy,
Charles Proxy, HttpCanary, or ProxyPin: it decrypts and inspects *the
device's own outbound traffic*, with a locally-generated root CA that the
user installs into their own user certificate store. It never targets
another person's traffic and requires the device owner's explicit
install-the-CA action to function at all. It's built as a debugging /
privacy-auditing surface — "what is this app / this site actually
sending" — not an interception tool against third parties.

## Module map

```
com.evan.lazlo/                          (module :app)
├── ai/            AiProvider interface + 3 implementations + factory
├── browser/       BrowserEngine interface, ChromiumEngine, and
│                  BrowserEngineLoader (loads GeckoEngine on demand —
│                  see dynamic-features/gecko-engine/ below)
├── proxy/         Local VpnService-backed MITM inspector + CA management,
│                  a Netty-based embedded proxy in proxy/net/
├── core/          Settings (DataStore), Keystore-backed secret storage
└── ui/            Compose UI: three screens + their ViewModels, tied
                   together by a bottom-nav Scaffold (MainActivity itself
                   is just a one-line host for this)

dynamic-features/gecko-engine/           (module :dynamic-features:gecko_engine)
└── com.evan.lazlo.browser.GeckoEngine   The only class here — GeckoView
                                          itself lives only in this
                                          on-demand module, never in the
                                          base APK
```

### 1. `ai/` — pluggable model backend

```kotlin
interface AiProvider {
    val id: String
    suspend fun isReady(): Boolean
    fun streamChat(messages: List<ChatMessage>): Flow<ChatToken>
}
```

Three implementations behind one interface, selected at runtime in
Settings and persisted via DataStore:

- **ApiKeyProvider** — generic BYOK REST client, config-driven (base URL,
  model, auth header shape, request/response shape) so it isn't tied to
  one provider. Two ready-made configs ship: `anthropicDefault()`
  (Anthropic's `/v1/messages`) and `openRouterDefault()` (OpenRouter's
  OpenAI-compatible `/v1/chat/completions`, routing to whichever model
  the user picks — GPT-4o by default). Each is its own backend in the
  picker with its own stored key (`SecretStore` keys by provider id, so
  "anthropic" and "openrouter" never collide) — `Settings.AiChoice.ApiKey`
  carries which one is active. Keys are written only to
  `EncryptedSharedPreferences` (AndroidX Security, backed by Android
  Keystore, hardware-backed on Pixel). Never logged, never included in
  crash reports (no crash reporter is included at all).
- **AiCoreProvider** — wraps the on-device **AICore / Gemini Nano**
  system service via the `com.google.android.gms.tflite` /
  `firebase-ai` on-device GenAI APIs (Pixel 9a supports this natively).
  Fully offline; nothing leaves the device on this path.
- **MediaPipeProvider** — MediaPipe LLM Inference API loading a
  user-supplied `.task`/gguf-converted model file from local storage
  (e.g. Gemma 2B/3 quantized, or any MediaPipe-compatible weights).
  Also fully offline; lets you run a model of your choosing rather than
  being limited to what AICore ships.

`AiProviderFactory` reads the persisted choice and constructs the active
provider; the chat UI only ever talks to the `AiProvider` interface, so
switching backends is a Settings toggle, not a code change.

**AICore provisioning.** `AiCoreProvider.isReady()` doesn't just build a
`GenerativeModel` — it calls `prepareInferenceEngine()`, which is what
actually triggers AICore's first-run model download on a fresh install.
The backend picker also exposes this explicitly: an AICore row that
isn't ready yet shows a "Set up Gemini Nano" button
(`ChatViewModel.setupAiCore()`) that runs provisioning up front with a
live progress bar fed by `AiCoreProvider.downloadState`, instead of only
ever happening silently the first time a message is sent. If AICore
fails, `AiCoreDiagnosis` (`ai/AiCoreProvider.kt`) turns the SDK's raw
`GenerativeAIException` — a numeric `errorCode` and a terse message —
into plain language instead of surfacing the raw code. The specific case
this was built for: **AICore error 8, `NOT_AVAILABLE` ("Required LLM
feature not found")**, which shows up even on genuinely AICore-supported
Pixel hardware and is a known, still-unresolved issue reported against
Google's own `android/ai-samples` repo (issues #3, #8, #24) — it means
Play Services hasn't switched the on-device LLM feature on for this
device/account yet, not that anything in this app is missing. It is not
fixable from inside Lazlo's code; `AiCoreDiagnosis` says so directly
rather than showing a dead-end error.

### 2. `browser/` — pluggable rendering engine

```kotlin
interface BrowserEngine {
    fun attach(container: ViewGroup)
    fun loadUrl(url: String)
    fun currentUrl(): String
    fun destroy()
}
```

- **ChromiumEngine** — thin wrapper around Android's system `WebView`
  (Chromium-based). Zero extra APK size, uses whatever WebView version
  is installed/updated via Play, sandboxed the way any WebView is. Lives
  in the base `:app` module.
- **GeckoEngine** — wraps Mozilla's **GeckoView** (`org.mozilla.geckoview`).
  Adds ~30–50MB, so it lives entirely in its own dynamic feature module,
  `dynamic-features/gecko-engine/` (Gradle project `:dynamic-features:gecko_engine`
  — the directory keeps the hyphen, but the module/split *name* Android
  actually uses can't contain one, only letters/digits/underscores; see
  the rename in `settings.gradle.kts`), delivered on-demand via Play
  Feature Delivery — never bundled into the base APK. **Verified, not
  assumed**: `dist:fusing dist:include="false"` in that module's
  manifest matters here — leaving it `"true"` was empirically observed
  to fuse GeckoView straight into a plain `assembleDebug` output anyway
  (the base APK dropped from 712MB to 85MB once that one attribute was
  corrected, confirmed by inspecting the built APK's contents directly,
  not by reading documentation and assuming). `BrowserEngineLoader` (in
  `:app`, since `:app` can't have a compile-time dependency on a class
  that lives only in the feature module) drives Play's
  `SplitInstallManager` to request the module and reports install
  progress, then constructs `GeckoEngine` via reflection once it's
  confirmed installed — the standard shape for "base module defines the
  interface, feature module provides the implementation."

Engine choice is a per-tab or global Settings toggle; both implementations
route their network layer through the same local proxy port when the
inspector is enabled (see below), so traffic capture works identically
regardless of engine.

**What's verified vs. not here:** the module split itself, the manifest
attributes, and the reflection-based loading all compile and were
confirmed by inspecting real build output (the APK-size drop above). The
actual `SplitInstallManager` install flow — a real download, progress
callbacks, the user-confirmation dialog for a large/cellular download —
needs a real device or Play's internal testing track to exercise; a
build sandbox with no Play Store infrastructure can't trigger it.

### 3. `proxy/` — local traffic inspector

Standard on-device MITM pattern, same one ProxyPin/HttpCanary/PCAPdroid use:

1. **CertificateAuthority** — generates a device-local root CA (BouncyCastle
   builds the certificate; the 4096-bit RSA key itself is generated
   *inside* Android Keystore with `PURPOSE_SIGN` only, non-extractable,
   hardware/TEE-backed where the device supports it — see the class doc
   for exactly how a real `BasicConstraints`/`KeyUsage`-bearing CA
   certificate gets attached to a Keystore-native key, since Keystore's
   own auto-generated placeholder cert can't function as a CA). Exports
   the public cert for the user to install via Settings → user CA store
   (requires explicit OS-level confirmation — the app cannot auto-install
   a trusted root).
2. **LeafCertificateFactory** — mints and caches a fresh leaf certificate
   per hostname on the fly, signed by the CA's Keystore-backed key, with
   proper `SubjectAlternativeName`/`ExtendedKeyUsage` extensions. This is
   the actual "MITM" step: a client that trusts the CA cert above accepts
   this leaf for that host without complaint.
3. **`proxy/net/` — the packet pump, Netty-based** — `TcpIpStack` reads
   raw IPv4 packets off the VPN's TUN fd and drives a minimal per-flow
   TCP state machine (`TcpFlow`/`IpV4Packet`/`TcpSegment`/`UdpDatagram`;
   this framing layer is hand-written since Netty has no TUN-native
   transport — nothing to plug in here). Each ESTABLISHED flow then
   hands off to `ConnectionRelay`, which *is* the "Netty + a MITM layer"
   embedded proxy: TLS termination on :443 runs through a real in-process
   Netty channel pair on `LocalChannel`/`LocalServerChannel` (Netty's own
   local transport for intra-JVM pipes) with `SniHandler` picking — and,
   via `LeafCertificateFactory`, minting — the right leaf cert per host,
   then a real `SslHandler` doing the actual handshake/record framing;
   the upstream connection to the real destination is a genuine
   `Bootstrap`-managed `NioSocketChannel`. Port :80 gets a plain relay,
   anything else raw passthrough, both also Netty-managed. UDP (mainly
   DNS) is passed straight through, unparsed. Every upstream channel is
   built from a `SocketChannel` that's `VpnService.protect()`-ed *before*
   Netty ever touches it — without that, the interceptor's own outbound
   connections would loop back into its own VPN routes.

   `LocalChannel`/`LocalServerChannel` were chosen deliberately over
   Netty's `EmbeddedChannel` (which an earlier version of this file used):
   `EmbeddedChannel` is built for driving a pipeline synchronously from a
   single caller — it's meant for unit-testing handlers — and this flow
   needs bytes fed in from one coroutine and drained from another.
   Forcing that through `EmbeddedChannel` meant hand-rolling a mutex
   around a class documented as not safe for that. The local-transport
   pair instead runs each side's handler on a real `EventLoop`, exactly
   like a real socket connection, so Netty's own per-channel
   single-threaded execution guarantee does the synchronization instead
   of anything this codebase writes itself.

   **Verified, not just written**: `app/src/test/java/.../NettyTlsTerminationTest.kt`
   is a real end-to-end JVM test — a genuine `javax.net.ssl.SSLSocket`
   client (the same shape as any real app's TLS stack) connects over a
   real loopback TCP socket to a Netty server pipeline built the same
   way `ConnectionRelay` builds one, and the test confirms the handshake
   succeeds, the leaf certificate presented is the exact one
   `LeafCertificateFactory` minted for the requested host (via SNI), and
   bytes round-trip correctly through the decrypted pipeline. That
   validates the interception mechanism itself; it doesn't (and can't,
   on the JVM) exercise the VPN/TUN plumbing that feeds real device
   traffic into it — see the scope note below.
4. **MitmVpnService** — the local-loopback `VpnService` that owns the TUN
   interface and drives `TcpIpStack`'s `pump()`/`shutdown()` across its
   lifecycle. TLS is terminated locally only — nothing is forwarded to
   any third-party relay. It promotes itself to a real foreground
   service with an ongoing "Lazlo is inspecting this device's traffic"
   notification the moment it starts — both because the manifest's
   `foregroundServiceType="specialUse"` declaration only actually takes
   effect once `startForeground()` is called (skipping it left the
   service exposed to normal background-service limits despite looking
   like a foreground service on paper), and because a self-interception
   privacy tool should never run invisibly.
5. **Traffic log** — in-memory ring buffer (optionally persisted to an
   encrypted local Room DB, off by default) of method/host/path/status/
   size, viewable in the Inspector tab (`ui/inspector/InspectorScreen.kt`).
   No export path unless the user
   explicitly taps "export" (writes a local file, no network send).

This only intercepts traffic from the device it runs on, and only after
the user installs the generated CA themselves — it can't be pointed at
someone else's traffic.

**Scope note on the packet pump specifically:** `TcpIpStack` targets *one
local, well-behaved TCP peer* — the device's own apps talking through a
VPN TUN — not a general-purpose internet-facing TCP/IP stack. An
out-of-order segment is dropped rather than buffered and reordered (the
sending app's own TCP stack retransmits, same as after any other dropped
packet); there's no congestion control, window scaling, or SACK. That's
a deliberate simplification appropriate to this app's actual job, not an
oversight. The IPv4/TCP codec and the certificate-signing logic both
have JVM unit tests (`app/src/test/`) and are verified by them; the TUN
read/write loop, the VPN-consent flow, and the loopback TLS bridge are
only verifiable on a real device or emulator — none of that could be
exercised in the sandbox this was built in, so treat `TcpIpStack` and
`ConnectionRelay` as needing real on-device testing before depending on
them, even though they compile clean and the pieces that can be tested
off-device pass.

### 4. `core/` — settings & secrets

- `Settings.kt` — Jetpack DataStore for non-secret prefs (engine choice,
  model choice, inspector on/off).
- Secrets (API keys, CA private key) live only in Android Keystore /
  `EncryptedSharedPreferences`, never in DataStore, never in plaintext
  files, never synced to backup (`android:allowBackup="false"` and
  `excludeAppDataFromAutoBackup` set on the secrets directory).

### No other app gets to see inside Lazlo

Beyond keeping secrets off the network and out of backups, Lazlo is
locked down so that nothing running alongside it on the same device —
not a malicious app, not a screen-recording tool, not a cloud backup —
can observe its contents:

- **`FLAG_SECURE`** (`MainActivity.onCreate`, set before any content is
  attached) blocks the standard OS capture paths for this window: the
  screenshot shortcut, another app's `MediaProjection`-based screen
  recording, non-secure external display/cast mirroring, and it blanks
  the Recents/task-switcher thumbnail to a generic placeholder instead of
  the live chat transcript or browsed page. This is the same mechanism
  banking apps and Android's own "Secure Folder" rely on. It cannot stop
  a device already compromised by a malicious Accessibility Service
  reading the view hierarchy directly — no app-level flag can — but it
  closes every capture path a normal app has available to it.
- **No exported components beyond the launcher.** `MainActivity` is the
  only `exported="true"` entry point (required for the launcher
  intent-filter to work at all); the VPN service is `exported="false"`
  and additionally gated behind the OS's own `BIND_VPN_SERVICE`
  permission/consent dialog. There is no `ContentProvider`, no
  deep-link/`BROWSABLE` intent-filter another app could target — nothing
  for another app to send an `Intent` at and pull data back out through.
- **`android:allowBackup="false"`** plus explicit empty
  `data_extraction_rules.xml`/`backup_rules.xml` — nothing about this app
  (settings, chat history, the local MITM CA) is eligible for cloud
  backup or device-to-device transfer, so it can't leak through a Google
  account or a phone-migration tool.
- **Explicit `network_security_config.xml`**: no cleartext traffic
  (applies to this app's own network calls *and* to WebView — a page a
  user browses to over `http://` simply won't load, so nothing routes in
  the clear on a hostile network), and only OS-shipped CAs are trusted
  roots. That second part matters specifically for this app: the traffic
  inspector mints its own local MITM CA to decrypt-and-reinspect *other*
  apps' connections, and that CA is deliberately never added as a trust
  anchor here, so Lazlo's own outbound connections (BYOK API calls, its
  own browsing) can never be intercepted by its own inspector, or by
  anything else with a user/admin-installed certificate on the device.
- **Secrets never touch the clipboard or logs.** There is no
  copy-to-clipboard path for API keys anywhere in the app, and nothing
  logs message content, keys, or intercepted traffic bodies via `Log.*`
  — Android sandboxes app-private storage from other apps by default, so
  the remaining question is always "did this app itself do something
  that leaks past the sandbox," and the answer here is checked, not
  assumed.

### 5. `ui/` — the three screens

`MainActivity` does nothing but set `LazloTheme { LazloApp() }` as its
Compose content; everything a user actually sees lives under `ui/`,
split by section rather than crammed into the Activity:

```
ui/
├── LazloApp.kt              Scaffold: branded TopAppBar + bottom
│                            NavigationBar switching between the three
│                            tabs below (LazloTab enum)
├── theme/LazloTheme.kt      Material3 color schemes (light/dark), sampled
│                            from the same brand colors as the launcher icon
├── chat/
│   ├── ChatScreen.kt        Transcript, input row, empty/loading/error
│   │                        states, the backend-picker and API-key dialogs
│   ├── ChatViewModel.kt     Owns the active AiProvider (via
│   │                        AiProviderFactory), streams replies, persists
│   │                        the backend choice via Settings/SecretStore
│   ├── ChatTranscript.kt    Pure list-editing helpers (append/stream/drop)
│   │                        — unit tested without Compose or Android
│   └── AiBackendCopy.kt     The plain-language, one-line explanation of
│                            each backend — pure, unit tested
├── browser/
│   ├── BrowserScreen.kt     Address bar, back/forward/reload, the engine
│   │                        picker (relocated here from the old toggle
│   │                        row), the AndroidView hosting whichever
│   │                        BrowserEngine is selected
│   ├── BrowserViewModel.kt  Persists engine choice, tracks address bar /
│   │                        current URL / loading / can-go-back-or-forward
│   ├── UrlBarInput.kt       Pure "is this a URL or a search" resolver —
│   │                        unit tested
│   └── BrowserEngineCopy.kt Plain-language engine explanations — pure,
│                            unit tested
└── inspector/
    ├── InspectorScreen.kt   On/off switch, the "why a certificate" card
    │                        with the install action, the live traffic list
    │                        and its empty state
    ├── InspectorViewModel.kt Persists the toggle, generates the CA off
    │                        the main thread, exposes TrafficLog.entries
    └── TrafficFormat.kt     Pure relative-time/byte-size/status formatting
                             for the traffic list — unit tested
```

Each screen's ViewModel is an `AndroidViewModel` constructed through a
small hand-written `ViewModelProvider.Factory` (no DI framework in this
project) and talks only to the same `ai`/`browser`/`proxy`/`core`
interfaces documented above — the UI layer doesn't reach around them.
Switching tabs doesn't lose state: each screen's ViewModel is hoisted at
the `viewModel()` call site tied to that screen's own composition, and
persisted choices (engine, AI backend, inspector on/off) round-trip
through `Settings`/`SecretStore` the same way the old single-screen
version did.

One deliberate trade-off: switching *away from* the Browser tab and back
tears down and recreates the underlying WebView/GeckoView (it's a native
Android `View`, not a Composable — Compose disposes it like any other
view leaving composition). `BrowserViewModel` remembers the last URL so
the recreated engine reopens the same page, but in-page scroll position
and browser history are lost on a round trip through another tab. Fixing
that fully (keeping all three tabs' native views alive simultaneously,
just hidden) is a reasonable follow-up, not done here.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | AI API calls, browsing |
| `BIND_VPN_SERVICE` / `android.permission.BIND_VPN_SERVICE` | local loopback capture for the inspector |
| `FOREGROUND_SERVICE` | keep the VPN/proxy alive while active |
| none else | no location, contacts, storage-wide access, etc. |

## Gradle (key deps)

```gradle
dependencies {
    implementation "androidx.datastore:datastore-preferences:1.1.1"
    implementation "androidx.security:security-crypto:1.1.0-alpha06"
    implementation "com.google.mediapipe:tasks-genai:0.10.14"
    implementation "com.google.ai.edge.aicore:aicore:0.0.1-exp02"
    // On-demand module install/progress — see BrowserEngineLoader.kt.
    implementation "com.google.android.play:feature-delivery:2.1.0"
    implementation "com.google.android.play:feature-delivery-ktx:2.1.0"
    // netty-all pulls in desktop-only native epoll/kqueue transport jars
    // that duplicate META-INF/INDEX.LIST inside an APK and aren't usable
    // on Android anyway (Android uses plain NIO), so depend on the
    // individual modules the embedded proxy (proxy/net/ConnectionRelay.kt)
    // actually needs instead of the "all" aggregate.
    implementation "io.netty:netty-common:4.1.110.Final"
    implementation "io.netty:netty-buffer:4.1.110.Final"
    implementation "io.netty:netty-transport:4.1.110.Final"
    implementation "io.netty:netty-codec:4.1.110.Final"
    implementation "io.netty:netty-handler:4.1.110.Final"
    implementation "org.bouncycastle:bcpkix-jdk18on:1.78.1"
}
```

GeckoView itself (`org.mozilla.geckoview:geckoview:130.0.20240913135723`,
date-stamped rather than plain semver, from Mozilla's own Maven repo —
`https://maven.mozilla.org/maven2/`, added alongside google()/
mavenCentral() in `settings.gradle.kts`) is a dependency of the
`dynamic-features/gecko-engine` module, not of `:app` — see the
`browser/` section above for why.

Three things worth knowing about the above, all already handled in
`app/build.gradle.kts`:

- Every `io.netty:*` jar ships an identical `META-INF/INDEX.LIST`, and
  the three `org.bouncycastle:*-jdk18on` jars all ship the same
  multi-release OSGi manifest fragment. Both need excluding in
  `packaging { resources { excludes += ... } }` or
  `mergeDebugJavaResource` fails on the duplicate.
- The AICore client library itself requires `minSdk 31` (its manifest
  declares that floor), which sets the floor for the whole app.
- Android dynamic-feature module names may only contain letters,
  digits, and underscores — no hyphens. `generateDebugFeatureMetadata`
  fails outright otherwise, which is how this was caught (empirically,
  not from reading the rule somewhere first).

## Build-out order

1. `core` (Settings + Keystore secrets) — everything else depends on it.
2. `ai` — start with `ApiKeyProvider` (fastest to test), then `AiCoreProvider`.
3. `browser` — `ChromiumEngine` first (no extra deps), `GeckoEngine` as
   the feature module once the interface is proven. Both done: GeckoEngine
   now lives in `dynamic-features/gecko-engine/`, loaded on demand.
4. `proxy` — CA generation and install flow first, VPN capture last (most
   moving parts). Both done, including the Netty-based embedded proxy.

## Current state

This repository holds the four backend modules with their interfaces
and a working implementation per class, plus a real three-screen `ui/`
layer (chat / browser / traffic inspector, tied together by a bottom
`NavigationBar`) that actually surfaces all of it — `MainActivity` is a
one-line Compose host, not where the app's logic lives. `gradle
:app:assembleDebug` succeeds against this tree (compileSdk 35, minSdk
31) and now also builds `:dynamic-features:gecko_engine` as a genuinely
separate on-demand module (confirmed by inspecting the resulting base
APK's contents, not just by the build succeeding). `gradle
:app:testDebugUnitTest` runs and passes 64 JVM-level unit tests under
`app/src/test/`: the IPv4/TCP codec, the CA/leaf certificate-signing
logic, a real end-to-end TLS handshake against the Netty MITM pipeline
(`NettyTlsTerminationTest`), AICore's error-code-to-plain-language
diagnosis (`AiCoreDiagnosisTest`), the Anthropic/OpenRouter BYOK request
and response shaping (`ApiKeyProviderConfigTest`), and the `ui/` layer's
pure logic (chat transcript folding, traffic-log formatting, address-bar
URL/search resolution, the backend/engine explainer copy).

What were previously the two heaviest documented skeletons are real
implementations, not stubs:

- **`CertificateAuthority`'s Keystore-backed private key** — the CA's
  RSA key is generated inside Android Keystore itself
  (`PURPOSE_SIGN`-only, non-extractable), and `LeafCertificateFactory`
  mints real per-host leaf certificates signed by it.
- **`TrafficInterceptor`'s packet pump** (`proxy/net/`) — a real IPv4/TCP
  parser and per-flow TCP state machine reading off the VPN's TUN fd,
  wired to a real Netty-based TLS-terminating relay (`SniHandler` +
  `LocalChannel`/`LocalServerChannel` + a real upstream `NioSocketChannel`
  — see the `proxy/` section above), matching this doc's original
  "Netty + a MITM layer" design rather than a from-scratch substitute.

Both are implemented for real and compile-verified, and the pieces with
no Android/VPN dependency (the codec, the certificate signing, and now
the Netty MITM handshake itself via `NettyTlsTerminationTest`) are
covered by passing JVM unit tests. What none of that testing covers —
because it can't be exercised outside a real Android device or
emulator — is the actual TUN read/write loop, the VPN-consent flow, and
the Netty relay under real device traffic, plus the GeckoView module's
actual `SplitInstallManager` download/install flow. Treat those as
implemented but **not yet validated on-device**, and budget for an
on-device pass (a real HTTPS request through the inspector, and a real
GeckoView module install, both watched in Android Studio's
debugger/logcat) before trusting this for anything beyond development.

**On the `ui/` layer specifically:** the Compose screens compile clean
and their pure logic (the parts factored out into plain Kotlin
objects — `ChatTranscript`, `TrafficFormat`, `UrlBarInput`, the copy
objects) has JVM unit test coverage. The Composables and ViewModels
themselves do not — Compose UI needs an emulator/device or a
`compose-ui-test`/Robolectric harness to exercise for real, neither of
which is set up in this project yet, so layout, the actual chat
streaming path against a live backend, the VPN-consent dialog flow, and
the certificate-install intent have only been checked by code review and
by confirming the app builds against the real library APIs (every
non-obvious signature — GeckoView's delegate callbacks, AICore's and
MediaPipe's generation APIs, `ViewModelProvider.Factory`,
`ServiceCompat.startForeground`, etc. — was checked against the actual
jars this project depends on, not assumed). Budget an on-device pass for
the UI the same way the inspector's packet pump already calls for one.
