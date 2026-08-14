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
| Traffic inspector | — | `CertificateAuthority`, `MitmVpnService`, `TrafficInterceptor` |

Secrets (API keys, the inspector's CA private key) live only in Android
Keystore-backed storage — never in DataStore, never in plaintext, never
included in backups.

## Project status

This repo currently holds the app's scaffolding: all four modules with
their interfaces and per-class implementations, a `MainActivity` wiring
a browser tab + engine picker + inspector toggle together, and the
Gradle project shell to open it in Android Studio. The two heaviest
pieces — the interceptor's actual packet pump, and Keystore-backed CA
private key storage — are left as documented skeletons. See
[`ARCHITECTURE.md`](ARCHITECTURE.md#build-out-order) for the intended
build-out order.

## Getting started

1. Open the project root in Android Studio (Koala/2024.1+) and let it
   sync — it's a standard Gradle Android project (AGP 8.6, Kotlin 2.0,
   Compose).
2. Run the `app` module on a device or emulator running API 26+.
3. In-app: pick a browser engine, optionally add an API key or point at
   a local model file for chat, and toggle the inspector if you want to
   see the app's own outbound traffic (this will prompt the standard
   Android VPN-consent dialog and, separately, a certificate-install
   step you have to approve yourself).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | AI API calls, browsing |
| `BIND_VPN_SERVICE` | local loopback capture for the inspector |
| `FOREGROUND_SERVICE` | keeps the VPN/proxy alive while the inspector is active |
| *(none else)* | no location, contacts, or broad storage access |

## License

[Mozilla Public License 2.0](LICENSE).
