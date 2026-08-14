package com.evan.lazlo.browser

import android.view.ViewGroup

enum class EngineKind { CHROMIUM, GECKO }

/**
 * Common surface over WebView (Chromium) and GeckoView (Firefox) so the
 * tab UI doesn't know or care which engine is rendering a given tab.
 */
interface BrowserEngine {
    val kind: EngineKind

    /** Fired with the new URL whenever navigation changes it — set before [attach] so the first page load isn't missed. */
    var onUrlChanged: ((String) -> Unit)?

    /** Fired true when a page starts loading, false when it finishes — for a simple loading indicator in the address bar. */
    var onLoadingChanged: ((Boolean) -> Unit)?

    fun attach(container: ViewGroup)
    fun loadUrl(url: String)
    fun goBack(): Boolean
    fun goForward(): Boolean

    /** Whether [goBack] would actually navigate right now — for enabling/disabling the back button. */
    fun canGoBack(): Boolean

    /** Whether [goForward] would actually navigate right now — for enabling/disabling the forward button. */
    fun canGoForward(): Boolean

    fun currentUrl(): String?
    fun setProxy(host: String, port: Int)
    fun destroy()
}
