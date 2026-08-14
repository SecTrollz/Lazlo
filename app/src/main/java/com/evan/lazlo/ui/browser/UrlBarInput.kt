package com.evan.lazlo.ui.browser

import java.net.URLEncoder

/**
 * Turns whatever the user typed into the address bar into something a
 * [com.evan.lazlo.browser.BrowserEngine] can load: a real URL if the
 * text looks like one, otherwise a search. Pure so this "what does the
 * address bar actually do" decision is unit-testable without a WebView.
 */
object UrlBarInput {

    /** DuckDuckGo's HTML-only endpoint — no JS required, keeps search working the same on both engines. */
    private const val SEARCH_BASE_URL = "https://duckduckgo.com/html/?q="

    fun resolve(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "about:blank"
        return if (looksLikeUrl(trimmed)) normalize(trimmed) else searchUrl(trimmed)
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.any { it.isWhitespace() }) return false
        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("about:")) return true
        // A bare host typed without a scheme, e.g. "example.com" or
        // "localhost:8080" — a dot (a TLD) or "localhost" is the cheap
        // signal that separates "this is a host" from "this is a search".
        val hostPart = input.substringBefore('/')
        return hostPart.startsWith("localhost") || hostPart.substringBefore(':').contains('.')
    }

    private fun normalize(input: String): String =
        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("about:")) {
            input
        } else {
            "https://$input"
        }

    private fun searchUrl(query: String): String =
        SEARCH_BASE_URL + URLEncoder.encode(query, "UTF-8")
}
