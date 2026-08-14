package com.evan.lazlo.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * System WebView (Chromium) engine. No extra APK weight; version tracks
 * whatever WebView Play has installed on the device.
 */
class ChromiumEngine(private val context: Context) : BrowserEngine {

    override val kind = EngineKind.CHROMIUM
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(container: ViewGroup) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.safeBrowsingEnabled = true
            webViewClient = WebViewClient()
        }
        webView = wv
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun loadUrl(url: String) { webView?.loadUrl(url) }
    override fun goBack(): Boolean = webView?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
    override fun goForward(): Boolean = webView?.let { if (it.canGoForward()) { it.goForward(); true } else false } ?: false
    override fun currentUrl(): String? = webView?.url

    override fun setProxy(host: String, port: Int) {
        // Routed at the VpnService layer (proxy/MitmVpnService) rather than
        // per-WebView, since WebView has no first-class per-instance proxy
        // API pre-API 33 androidx.webkit ProxyController. On API 33+ this
        // can additionally use androidx.webkit.ProxyController for finer
        // per-tab control if desired.
    }

    override fun destroy() {
        webView?.destroy()
        webView = null
    }
}
