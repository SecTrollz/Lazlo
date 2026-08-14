package com.evan.lazlo.browser

import android.view.ViewGroup

enum class EngineKind { CHROMIUM, GECKO }

/**
 * Common surface over WebView (Chromium) and GeckoView (Firefox) so the
 * tab UI doesn't know or care which engine is rendering a given tab.
 */
interface BrowserEngine {
    val kind: EngineKind

    fun attach(container: ViewGroup)
    fun loadUrl(url: String)
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun currentUrl(): String?
    fun setProxy(host: String, port: Int)
    fun destroy()
}
