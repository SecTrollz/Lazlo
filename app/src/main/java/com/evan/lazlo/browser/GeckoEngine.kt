package com.evan.lazlo.browser

import android.content.Context
import android.view.ViewGroup
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
    private var geckoView: GeckoView? = null
    private var session: GeckoSession? = null

    companion object {
        @Volatile private var runtime: GeckoRuntime? = null
        private fun runtime(ctx: Context): GeckoRuntime = runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                ctx,
                GeckoRuntimeSettings.Builder()
                    .trackingProtectionCategories(
                        GeckoRuntimeSettings.TrackingProtection.CATEGORY_AD or
                        GeckoRuntimeSettings.TrackingProtection.CATEGORY_ANALYTIC or
                        GeckoRuntimeSettings.TrackingProtection.CATEGORY_SOCIAL
                    )
                    .build()
            ).also { runtime = it }
        }
    }

    override fun attach(container: ViewGroup) {
        val gv = GeckoView(context)
        val sess = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        sess.open(runtime(context))
        gv.setSession(sess)
        geckoView = gv
        session = sess
        container.addView(gv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun loadUrl(url: String) { session?.loadUri(url) }
    override fun goBack(): Boolean { session?.goBack(); return true }
    override fun goForward(): Boolean { session?.goForward(); return true }
    override fun currentUrl(): String? = null // read via NavigationDelegate.onLocationChange in a full impl

    override fun setProxy(host: String, port: Int) {
        // Also routed at the VpnService layer; GeckoRuntimeSettings has no
        // dynamic per-session proxy switch, so proxying is handled
        // uniformly for both engines by MitmVpnService below.
    }

    override fun destroy() {
        session?.close()
        geckoView = null
        session = null
    }
}
