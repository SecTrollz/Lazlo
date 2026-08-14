package com.evan.lazlo.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * System WebView (Chromium) engine. No extra APK weight; version tracks
 * whatever WebView Play has installed on the device.
 */
class ChromiumEngine(private val context: Context) : BrowserEngine {

    override val kind = EngineKind.CHROMIUM
    override var onUrlChanged: ((String) -> Unit)? = null
    override var onLoadingChanged: ((Boolean) -> Unit)? = null
    override var onTitleChanged: ((String) -> Unit)? = null
    override var onDownloadRequested: ((DownloadRequest) -> Unit)? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(container: ViewGroup) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    onLoadingChanged?.invoke(true)
                    onUrlChanged?.invoke(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onLoadingChanged?.invoke(false)
                    onUrlChanged?.invoke(url)
                }
            }
            // Title comes from WebChromeClient, not WebViewClient — a
            // separate delegate for "chrome"-level page state (title,
            // favicon, JS dialogs) as opposed to navigation events.
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    title?.let { onTitleChanged?.invoke(it) }
                }
            }
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                onDownloadRequested?.invoke(DownloadRequest(url, contentDisposition, mimeType))
            }
        }
        webView = wv
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun loadUrl(url: String) { webView?.loadUrl(url) }
    override fun goBack(): Boolean = webView?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
    override fun goForward(): Boolean = webView?.let { if (it.canGoForward()) { it.goForward(); true } else false } ?: false
    override fun canGoBack(): Boolean = webView?.canGoBack() ?: false
    override fun canGoForward(): Boolean = webView?.canGoForward() ?: false
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
