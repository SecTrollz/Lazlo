package com.evan.lazlo.browser

import android.view.ViewGroup

enum class EngineKind { CHROMIUM, GECKO }

/**
 * A file the engine handed off instead of rendering — enough to start a
 * real download via Android's `DownloadManager`. Both engines adapt
 * their own native download hook (`WebView.setDownloadListener` /
 * `GeckoSession.ContentDelegate.onExternalResponse`) into this same shape
 * so the UI layer only needs to handle one.
 */
data class DownloadRequest(val url: String, val contentDisposition: String?, val mimeType: String?)

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

    /** Fired whenever the page reports a title — for history entries and the tab strip's tab labels. */
    var onTitleChanged: ((String) -> Unit)?

    /** Fired when the engine hands off a response it won't render (a file download) instead of navigating to it. */
    var onDownloadRequested: ((DownloadRequest) -> Unit)?

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
