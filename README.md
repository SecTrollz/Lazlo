<p align="center">
  <img src="docs/assets/lazlo-emblem.png" alt="Lazlo emblem" width="160" />
</p>

<h1 align="center">Lazlo</h1>

<p align="center">
  A minimal, privacy-first Android app: swappable AI chat, swappable browser engine,
  and a local traffic inspector — for your own device's own traffic only.
</p>

---

## What Lazlo is

Lazlo combines three things behind clean interfaces, so none of them is
locked in:

- **Chat with the AI backend you choose** — bring your own API key, or run
  fully offline with an on-device model (Gemini Nano via AICore, or any
  MediaPipe-compatible model file you supply).
- **Browse with the engine you choose** — the system WebView (Chromium)
  or GeckoView (Firefox's engine), switchable per tab.
- **Inspect your own device's traffic** — a local, self-interception MITM
  proxy in the same category as mitmproxy, Charles Proxy, or HttpCanary:
  it decrypts and shows what *this device* is sending, using a
  locally-generated root CA that only you install, only on your own
  phone. It cannot be pointed at anyone else's traffic.

No telemetry. No analytics SDKs. No first-party backend. The app talks
only to (a) whatever AI endpoint you configure and (b) the sites you
browse to.

Full technical breakdown, module-by-module, is in
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Why

Most "AI browser" or "privacy browser" apps ask you to trust a vendor's
backend, a vendor's model, and a vendor's claims about what leaves your
device. Lazlo's answer is to make every one of those a swappable,
inspectable piece instead of a black box:

- Don't trust the AI vendor? Point it at your own key, or skip the
  network path entirely and run on-device.
- Don't trust the browser engine? Swap it.
- Don't trust *Lazlo itself*? Turn on the inspector and watch exactly
  what the app sends, on your own device, under your own locally-issued
  certificate.

## Module map

```
com.evan.lazlo/
├── ai/            AiProvider interface + BYOK / AICore / MediaPipe implementations
├── browser/       BrowserEngine interface + WebView / GeckoView implementations
├── proxy/         Local VpnService-backed traffic inspector + CA management
└── core/          Settings (DataStore), Keystore-backed secret storage
```

| Layer | Interface | Implementations |
|---|---|---|
| AI backend | `AiProvider` | `ApiKeyProvider` (BYOK REST), `AiCoreProvider` (Gemini Nano, on-device), `MediaPipeProvider` (local model file, on-device) |
| Browser engine | `BrowserEngine` | `ChromiumEngine` (system WebView), `GeckoEngine` (GeckoView) |
| Traffic inspector | — | `CertificateAuthority`, `LeafCertificateFactory`, `MitmVpnService`, `TrafficInterceptor` + the packet pump in `proxy/net/` |

Secrets (API keys, the inspector's CA private key) live only in Android
Keystore-backed storage — never in DataStore, never in plaintext, never
included in backups.

## Project status

All four backend modules are implemented, not stubbed, and there's now a
real UI on top of them instead of a placeholder single screen: three
sections — **Chat**, **Browser**, **Inspector** — under a Material3
bottom navigation bar, each wired to the module it fronts
(`AiProviderFactory`, `BrowserEngine`, `TrafficLog`/`CertificateAuthority`
respectively). The Gradle project shell opens and builds cleanly in
Android Studio. That includes what were previously the two heaviest open
backend pieces — the CA's Keystore-backed private key and the
inspector's actual TUN packet pump + TLS-terminating relay
(`proxy/net/`) — both real implementations with passing JVM unit tests
for the parts that don't need a device, and the same is true of the new
UI layer's pure logic (message-transcript folding, traffic-log
formatting, address-bar URL/search resolution, and the plain-language
backend/engine explanations are all unit tested; see
[`ARCHITECTURE.md`](ARCHITECTURE.md#5-ui--the-three-screens)).

What's *not* yet done is on-device validation — of the VPN/TUN path (as
before) and now also of the Compose UI itself (layout, the live chat
streaming path, the VPN-consent and certificate-install flows), none of
which can be exercised on a real device or emulator from inside a build
sandbox. See [`ARCHITECTURE.md`](ARCHITECTURE.md#current-state) for the
honest current-state breakdown before relying on this for anything
beyond development.

## Getting started

1. Open the project root in Android Studio (Koala/2024.1+) and let it
   sync — it's a standard Gradle Android project (AGP 8.6, Kotlin 2.0,
   Compose). `./gradlew assembleDebug` builds cleanly from the command
   line too, and `./gradlew testDebugUnitTest` runs the JVM-level unit
   tests (IPv4/TCP codec, CA/leaf certificate signing, and the UI
   layer's pure logic).
2. Run the `app` module on a device or emulator running API 31+ (the
   AICore on-device provider's own client library sets that floor).
3. In-app, use the three tabs at the bottom: **Chat** to pick/switch an
   AI backend (add an API key, or use an on-device model) and talk to
   it; **Browser** to pick an engine and browse, with a real address
   bar; **Inspector** to turn traffic capture on (this prompts the
   standard Android VPN-consent dialog) and install the local
   certificate that lets it decrypt this device's own HTTPS traffic —
   both steps you have to approve yourself, explained in-app before you
   do.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | AI API calls, browsing |
| `BIND_VPN_SERVICE` | local loopback capture for the inspector |
| `FOREGROUND_SERVICE` | keeps the VPN/proxy alive while the inspector is active |
| *(none else)* | no location, contacts, or broad storage access |

## License

[Mozilla Public License 2.0](LICENSE).
