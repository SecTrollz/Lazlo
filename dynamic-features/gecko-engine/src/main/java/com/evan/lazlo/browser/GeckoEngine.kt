package com.evan.lazlo.browser

import android.content.Context
import android.view.ViewGroup
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView (Firefox) engine. Shipped as a Play Feature Delivery
 * on-demand module (see /dynamic-features/gecko-engine) so the base
 * APK stays small; this class is only loaded once that module installs.
 */
class GeckoEngine(private val context: Context) : BrowserEngine {

    override val kind = EngineKind.GECKO
    override var onUrlChanged: ((String) -> Unit)? = null
    override var onLoadingChanged: ((Boolean) -> Unit)? = null
    override var onTitleChanged: ((String) -> Unit)? = null
    override var onDownloadRequested: ((DownloadRequest) -> Unit)? = null
    private var geckoView: GeckoView? = null
    private var session: GeckoSession? = null

    @Volatile private var lastKnownUrl: String? = null
    @Volatile private var canGoBackState = false
    @Volatile private var canGoForwardState = false

    companion object {
        @Volatile private var runtime: GeckoRuntime? = null
        private fun runtime(ctx: Context): GeckoRuntime = runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                ctx,
                GeckoRuntimeSettings.Builder()
                    .contentBlocking(
                        ContentBlocking.Settings.Builder()
                            .antiTracking(
                                ContentBlocking.AntiTracking.AD or
                                ContentBlocking.AntiTracking.ANALYTIC or
                                ContentBlocking.AntiTracking.SOCIAL
                            )
                            .build()
                    )
                    .build()
            ).also { runtime = it }
        }
    }

    override fun attach(container: ViewGroup) {
        val gv = GeckoView(context)
        val sess = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        sess.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                lastKnownUrl = url
                url?.let { onUrlChanged?.invoke(it) }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canGoBackState = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canGoForwardState = canGoForward
            }
        }
        sess.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                onLoadingChanged?.invoke(true)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                onLoadingChanged?.invoke(false)
            }
        }
        sess.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let { onTitleChanged?.invoke(it) }
            }

            // Fired for a response GeckoView won't render itself — a file
            // download, same trigger as WebView's setDownloadListener.
            override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
                onDownloadRequested?.invoke(
                    DownloadRequest(
                        url = response.uri,
                        contentDisposition = response.headers["content-disposition"],
                        mimeType = response.headers["content-type"],
                    ),
                )
            }
        }
        sess.open(runtime(context))
        gv.setSession(sess)
        geckoView = gv
        session = sess
        container.addView(gv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun loadUrl(url: String) { session?.loadUri(url) }

    override fun goBack(): Boolean {
        if (!canGoBackState) return false
        session?.goBack()
        return true
    }

    override fun goForward(): Boolean {
        if (!canGoForwardState) return false
        session?.goForward()
        return true
    }

    override fun canGoBack(): Boolean = canGoBackState
    override fun canGoForward(): Boolean = canGoForwardState
    override fun currentUrl(): String? = lastKnownUrl

    override fun setProxy(host: String, port: Int) {
        // Also routed at the VpnService layer; GeckoRuntimeSettings has no
        // dynamic per-session proxy switch, so proxying is handled
        // uniformly for both engines by MitmVpnService below.
    }

    override fun destroy() {
        // Detach the session from the view *before* closing it: GeckoView holds
        // the session and its compositor display until releaseSession(), so
        // closing first leaves the view bound to a dead session. This engine is
        // destroyed and rebuilt on every engine/tab switch (see BrowserScreen),
        // so a session leaked per teardown adds up fast.
        runCatching { geckoView?.releaseSession() }
        session?.let { s ->
            // Delegates outlive the close otherwise, and a late callback would
            // fire into a ViewModel this engine no longer belongs to.
            s.navigationDelegate = null
            s.progressDelegate = null
            s.contentDelegate = null
            runCatching { s.close() }
        }
        geckoView = null
        session = null
    }
}
