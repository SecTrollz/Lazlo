package com.evan.lazlo.ui.browser

import com.evan.lazlo.browser.EngineKind

/**
 * Plain-language, one-line explanations of each browser engine choice.
 * Pure so the copy is unit-testable and lives in one place.
 */
object BrowserEngineCopy {

    fun explanation(kind: EngineKind): String = when (kind) {
        EngineKind.CHROMIUM ->
            "Your device's built-in WebView — the real Chromium engine, the same one Chrome itself runs on. Android layers a couple of tells on top by default (a \"; wv)\" marker in the User-Agent, an \"Android WebView\" Client Hints brand) that let sites fingerprint it as an embedded browser and block it; this engine strips both, so it presents as the genuine Chrome build it actually is. No extra download."
        EngineKind.GECKO ->
            "Firefox's engine (GeckoView) — the default, since it's fully independent of the device's built-in WebView rather than a hardened version of it. Its own tracking protection and certificate checks too. Downloads once, the first time it's needed."
    }

    fun shortLabel(kind: EngineKind): String = when (kind) {
        EngineKind.CHROMIUM -> "Chromium (hardened WebView)"
        EngineKind.GECKO -> "GeckoView (Firefox engine) — default"
    }
}
