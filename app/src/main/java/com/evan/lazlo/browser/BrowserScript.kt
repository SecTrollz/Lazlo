package com.evan.lazlo.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-authored JS snippet that [BrowserScreen] runs, via
 * [BrowserEngine.runScript], against any page whose URL contains
 * [urlContains] once that page finishes loading — the browser tab's
 * userscript/automation layer, for scripting repetitive actions on sites
 * a user actually uses or testing pages they control, the same job
 * Tampermonkey/Greasemonkey userscripts or a site's own browser
 * automation does. The browser-tab analogue of [com.evan.lazlo.proxy.net.RewriteRule]
 * (proxy-level, edits request/response bytes) and the Inspector's traffic
 * plugins: same per-site, user-controlled, on/off shape, just acting on
 * the live DOM after a normal page load instead of the wire.
 *
 * Scripts run inside the target page's own JS context — same origin,
 * same sandbox, same restrictions any page's own script would have.
 * They have no access to anything outside that page: no other tabs, no
 * device APIs, no Lazlo app state or stored data.
 */
data class BrowserScript(
    val id: String,
    val enabled: Boolean = true,
    val name: String = "",
    val urlContains: String = "",
    val code: String = "",
) {
    /**
     * Unlike [com.evan.lazlo.proxy.net.RewriteRule.matchesHost], a blank
     * [urlContains] matches nothing rather than every page — a rewrite
     * rule with no host filter still only ever touches traffic that's
     * already flowing through the proxy the user turned on, but a script
     * with no match pattern would silently run on literally every page
     * loaded in the browser, which isn't a sane default for arbitrary JS.
     */
    fun matchesUrl(url: String): Boolean = urlContains.isNotBlank() && url.contains(urlContains, ignoreCase = true)
}

/** Pure JSON encode/decode for a [BrowserScript] list — same split as RewriteRuleCodec. */
object BrowserScriptCodec {

    fun encode(scripts: List<BrowserScript>): String =
        JSONArray().apply {
            scripts.forEach { s ->
                put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("enabled", s.enabled)
                        put("name", s.name)
                        put("urlContains", s.urlContains)
                        put("code", s.code)
                    },
                )
            }
        }.toString()

    fun decode(json: String?): List<BrowserScript> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
                add(
                    BrowserScript(
                        id = id,
                        enabled = obj.optBoolean("enabled", true),
                        name = obj.optString("name", ""),
                        urlContains = obj.optString("urlContains", ""),
                        code = obj.optString("code", ""),
                    ),
                )
            }
        }
    }
}
