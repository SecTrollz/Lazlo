package com.evan.lazlo.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * System WebView engine. No extra APK weight; version tracks whatever
 * WebView Play has installed on the device.
 *
 * WebView *is* Chromium — the same rendering engine as desktop/mobile
 * Chrome, built from the same source. What lets a site tell them apart
 * (and block the "in-app browser" one) isn't the engine, it's a couple of
 * signals Android layers on top of it: a `; wv)` token in the
 * User-Agent string, and — on newer WebView builds — an "Android WebView"
 * entry in the User-Agent Client Hints brand list
 * (`navigator.userAgentData`, the `Sec-CH-UA` request header).
 * [hardenFingerprint] strips both, so this presents as the real Chrome
 * build it's actually running instead of the wrapper around it.
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
            hardenFingerprint(settings)
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

    /**
     * Strips WebView's two Chrome-vs-WebView tells off [settings]:
     *
     * 1. The User-Agent string, via [ChromiumUserAgent] — pulled out as a
     *    pure function so that transform is unit-testable on its own.
     * 2. The "Android WebView" brand in User-Agent Client Hints
     *    (`navigator.userAgentData`, the `Sec-CH-UA` request header), on
     *    WebView builds that expose it — gated on
     *    [WebViewFeature.USER_AGENT_METADATA] since the androidx.webkit
     *    API to touch it only works where the underlying WebView supports
     *    it; older WebView builds don't send this brand entry in the
     *    first place, so there's nothing to strip. Wrapped in
     *    [runCatching] because this rebuilds the whole [UserAgentMetadata]
     *    object field-by-field off a live WebView API — more exposed to a
     *    version quirk than the plain string edit above, and there's
     *    nothing this app can usefully do about a WebView build that
     *    rejects it beyond leaving Client Hints at WebView's own default.
     */
    private fun hardenFingerprint(settings: WebSettings) {
        settings.userAgentString = ChromiumUserAgent.stripEmbeddedBrowserMarkers(WebSettings.getDefaultUserAgent(context))

        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val current = WebSettingsCompat.getUserAgentMetadata(settings)
                val strippedBrands = current.brandVersionList.filterNot {
                    it.brand.contains("WebView", ignoreCase = true)
                }
                if (strippedBrands.size != current.brandVersionList.size) {
                    val rebuilt = UserAgentMetadata.Builder()
                        .setBrandVersionList(strippedBrands)
                        .setFullVersion(current.fullVersion)
                        .setPlatform(current.platform)
                        .setPlatformVersion(current.platformVersion)
                        .setArchitecture(current.architecture)
                        .setModel(current.model)
                        .setMobile(current.isMobile)
                        .setBitness(current.bitness)
                        .setWow64(current.isWow64)
                        .apply { current.formFactors?.let { setFormFactors(it) } }
                        .build()
                    WebSettingsCompat.setUserAgentMetadata(settings, rebuilt)
                }
            }
        }
    }

    override fun loadUrl(url: String) { webView?.loadUrl(url) }

    /** No result callback needed — [runScript]'s callers (auto-run on page load) don't read a return value back. */
    override fun runScript(js: String) { webView?.evaluateJavascript(js, null) }
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
