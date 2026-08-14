package com.evan.lazlo.ui.browser

import com.evan.lazlo.browser.EngineKind

/**
 * Plain-language, one-line explanations of each browser engine choice.
 * Pure so the copy is unit-testable and lives in one place.
 */
object BrowserEngineCopy {

    fun explanation(kind: EngineKind): String = when (kind) {
        EngineKind.CHROMIUM ->
            "Your device's built-in WebView (Chromium) — the same engine most apps use for in-app web pages. No extra download, stays current via Play."
        EngineKind.GECKO ->
            "Firefox's engine (GeckoView) instead of your device's built-in one, with its own independent tracking protection and certificate checks."
    }

    fun shortLabel(kind: EngineKind): String = when (kind) {
        EngineKind.CHROMIUM -> "System WebView"
        EngineKind.GECKO -> "GeckoView (Firefox engine)"
    }
}
