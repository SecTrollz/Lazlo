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
com.evan.lazlo/
├── ai/            AiProvider interface + 3 implementations + factory
├── browser/       BrowserEngine interface + WebView/GeckoView impls
├── proxy/         Local VpnService-backed MITM inspector + CA management
└── core/          Settings (DataStore), Keystore-backed secret storage
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

- **ApiKeyProvider** — generic BYOK REST client. Key is written only to
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
  is installed/updated via Play, sandboxed the way any WebView is.
- **GeckoEngine** — wraps Mozilla's **GeckoView** (`org.mozilla.geckoview`).
  Adds ~30–50MB to the APK (ship as a Play Feature Delivery on-demand
  module so it's not in the base install) but gives you Firefox's
  tracking-protection lists, independent cert validation, and an engine
  that isn't Google's.

Engine choice is a per-tab or global Settings toggle; both implementations
route their network layer through the same local proxy port when the
inspector is enabled (see below), so traffic capture works identically
regardless of engine.

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
3. **`proxy/net/` — the packet pump** — `TcpIpStack` reads raw IPv4
   packets off the VPN's TUN fd, drives a minimal per-flow TCP state
   machine (`TcpFlow`/`IpV4Packet`/`TcpSegment`/`UdpDatagram`), and hands
   each ESTABLISHED flow to `ConnectionRelay`: TLS termination + a real
   upstream TLS connection on :443 (bridged through a real loopback
   `SSLServerSocket`/`SSLSocket` pair using the leaf from
   `LeafCertificateFactory`, rather than a hand-rolled `SSLEngine`
   driver), a plain relay on :80, or raw passthrough on any other port
   so the rest of the device's traffic keeps working. UDP (mainly DNS)
   is passed straight through, unparsed. Every upstream socket is routed
   through `VpnService.protect()` first — without that, the interceptor's
   own outbound connections would loop back into its own VPN routes.
4. **MitmVpnService** — the local-loopback `VpnService` that owns the TUN
   interface and drives `TcpIpStack`'s `pump()`/`shutdown()` across its
   lifecycle. TLS is terminated locally only — nothing is forwarded to
   any third-party relay.
5. **Traffic log** — in-memory ring buffer (optionally persisted to an
   encrypted local Room DB, off by default) of method/host/path/status/
   size, viewable in a Compose screen. No export path unless the user
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
    // GeckoView is date-stamped, not plain semver, and lives on Mozilla's
    // own Maven repo (https://maven.mozilla.org/maven2/) — add that
    // repository alongside google()/mavenCentral() in settings.gradle.kts.
    implementation "org.mozilla.geckoview:geckoview:130.0.20240913135723" // as dynamic feature module
    // No Netty: proxy/net/TcpIpStack.kt is a small hand-written IPv4/TCP
    // codec, and TLS termination bridges through a real loopback
    // SSLSocket/SSLServerSocket pair — see proxy/'s section above.
    implementation "org.bouncycastle:bcpkix-jdk18on:1.78.1"
}
```

Two things worth knowing about the above, both already handled in
`app/build.gradle.kts`:

- The three `org.bouncycastle:*-jdk18on` jars all ship the same
  multi-release OSGi manifest fragment; it needs excluding in
  `packaging { resources { excludes += ... } }` or
  `mergeDebugJavaResource` fails on the duplicate.
- The AICore client library itself requires `minSdk 31` (its manifest
  declares that floor), which sets the floor for the whole app.

## Build-out order

1. `core` (Settings + Keystore secrets) — everything else depends on it.
2. `ai` — start with `ApiKeyProvider` (fastest to test), then `AiCoreProvider`.
3. `browser` — `ChromiumEngine` first (no extra deps), `GeckoEngine` as
   the feature module once the interface is proven.
4. `proxy` — CA generation and install flow first, VPN capture last (most
   moving parts).

## Current state

This repository holds the four modules with their interfaces and a
working implementation per class, a `MainActivity` that wires a
`BrowserEngine` tab, the engine/inspector toggles, and the VPN-consent
flow together, and the Gradle project shell needed to open and build it
in Android Studio. `./gradlew assembleDebug` succeeds against this tree
(compileSdk 35, minSdk 31), and `./gradlew testDebugUnitTest` runs and
passes the JVM-level unit tests under `app/src/test/` (the IPv4/TCP
codec and the CA/leaf certificate-signing logic).

What were previously the two heaviest documented skeletons are now real
implementations, not stubs:

- **`CertificateAuthority`'s Keystore-backed private key** — the CA's
  RSA key is generated inside Android Keystore itself
  (`PURPOSE_SIGN`-only, non-extractable), and `LeafCertificateFactory`
  mints real per-host leaf certificates signed by it.
- **`TrafficInterceptor`'s packet pump** (`proxy/net/`) — a real IPv4/TCP
  parser and per-flow TCP state machine reading off the VPN's TUN fd,
  wired to a real TLS-terminating relay via a loopback
  `SSLServerSocket`/`SSLSocket` bridge.

Both are implemented for real and compile-verified, and the pieces with
no Android/VPN dependency (the codec, the certificate signing) are
covered by passing JVM unit tests. What none of that testing covers —
because it can't be exercised outside a real Android device or
emulator — is the actual TUN read/write loop, the VPN-consent flow, and
the loopback TLS bridge under real traffic. Treat those as implemented
but **not yet validated on-device**, and budget for an on-device pass
(a real HTTPS request through the inspector, watched in Android Studio's
debugger/logcat) before trusting this for anything beyond development.
