package com.evan.lazlo.proxy.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * One user-defined rewrite rule, applied by [RewriteEngine] to matching
 * HTTP requests/responses as they pass through [ConnectionRelay]. Empty
 * [hostContains] matches every host. At least one of the header/body
 * actions should be set for a rule to do anything; [RewriteEngine]
 * simply no-ops on a rule with none set rather than treating it as an
 * error — a rule mid-edit in the UI shouldn't be able to corrupt traffic.
 */
data class RewriteRule(
    val id: String,
    val enabled: Boolean = true,
    val label: String = "",
    val hostContains: String = "",
    val appliesToRequest: Boolean = true,
    val appliesToResponse: Boolean = false,
    val setHeaderName: String? = null,
    val setHeaderValue: String? = null,
    val removeHeaderName: String? = null,
    val bodyFind: String? = null,
    val bodyReplace: String? = null,
) {
    fun matchesHost(host: String): Boolean = hostContains.isBlank() || host.contains(hostContains, ignoreCase = true)
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

/** Pure JSON encode/decode for a [RewriteRule] list — unit-testable without DataStore, same split this codebase uses everywhere else for persisted lists. */
object RewriteRuleCodec {

    fun encode(rules: List<RewriteRule>): String =
        JSONArray().apply {
            rules.forEach { r ->
                put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("enabled", r.enabled)
                        put("label", r.label)
                        put("hostContains", r.hostContains)
                        put("appliesToRequest", r.appliesToRequest)
                        put("appliesToResponse", r.appliesToResponse)
                        r.setHeaderName?.let { put("setHeaderName", it) }
                        r.setHeaderValue?.let { put("setHeaderValue", it) }
                        r.removeHeaderName?.let { put("removeHeaderName", it) }
                        r.bodyFind?.let { put("bodyFind", it) }
                        r.bodyReplace?.let { put("bodyReplace", it) }
                    },
                )
            }
        }.toString()

    fun decode(json: String?): List<RewriteRule> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
                add(
                    RewriteRule(
                        id = id,
                        enabled = obj.optBoolean("enabled", true),
                        label = obj.optString("label", ""),
                        hostContains = obj.optString("hostContains", ""),
                        appliesToRequest = obj.optBoolean("appliesToRequest", true),
                        appliesToResponse = obj.optBoolean("appliesToResponse", false),
                        setHeaderName = obj.optStringOrNull("setHeaderName"),
                        setHeaderValue = obj.optStringOrNull("setHeaderValue"),
                        removeHeaderName = obj.optStringOrNull("removeHeaderName"),
                        bodyFind = obj.optStringOrNull("bodyFind"),
                        bodyReplace = obj.optStringOrNull("bodyReplace"),
                    ),
                )
            }
        }
    }
}
