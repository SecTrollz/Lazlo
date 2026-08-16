package com.evan.lazlo.browser

/**
 * The pure half of [ChromiumEngine]'s fingerprint hardening: strips the
 * two tells Android's WebView adds on top of Chromium's own User-Agent
 * string that real Chrome doesn't send. Split out as a plain string
 * transform — rather than inlined in [ChromiumEngine] — so it's
 * unit-testable without a live WebView, the same way [UrlBarInput] and
 * [com.evan.lazlo.proxy.net.RewriteRuleCodec] pull their pure logic out
 * of code that otherwise needs a real Android environment to run.
 */
object ChromiumUserAgent {

    /**
     * Matches the `Version/4.0 ` (or whatever version number a given
     * OS/WebView build stamps in) token WebView's default UA inserts
     * right before the `Chrome/…` token — real Chrome never has this.
     */
    private val VERSION_TOKEN = Regex("""\bVersion/\d+(\.\d+)* """)

    /**
     * Takes whatever [android.webkit.WebSettings.getDefaultUserAgent]
     * returns for this device/WebView build and removes the `; wv)`
     * marker and the [VERSION_TOKEN] segment — the two things that make
     * a WebView's UA distinguishable from real Chrome's on the same
     * build. Everything else (Android version, device model, the actual
     * Chromium version) is left untouched, since that's what's really
     * running and is what makes the result credible rather than just a
     * different, equally fabricated tell.
     */
    fun stripEmbeddedBrowserMarkers(defaultUa: String): String =
        defaultUa.replace("; wv)", ")").replace(VERSION_TOKEN, "")
}
