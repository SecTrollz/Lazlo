package com.evan.lazlo.core

import org.json.JSONArray
import org.json.JSONObject

/** One saved page: a history visit or a bookmark, keyed by [url]. */
data class BrowserRecord(val url: String, val title: String, val timestampMillis: Long)

/** One file Lazlo's browser handed off to Android's DownloadManager. */
data class DownloadRecord(val downloadManagerId: Long, val url: String, val fileName: String, val timestampMillis: Long)

/**
 * Pure JSON encode/decode for [BrowserRecord]/[DownloadRecord] lists —
 * split out from [BrowserDataStore] so the actual serialization logic is
 * unit-testable without DataStore or a Context, matching how the rest of
 * this codebase separates persistence plumbing from the plain-Kotlin
 * logic underneath it.
 */
object BrowserRecordCodec {

    fun encodeRecords(records: List<BrowserRecord>): String =
        JSONArray().apply {
            records.forEach { r ->
                put(
                    JSONObject().apply {
                        put("url", r.url)
                        put("title", r.title)
                        put("ts", r.timestampMillis)
                    },
                )
            }
        }.toString()

    fun decodeRecords(json: String?): List<BrowserRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").takeIf { it.isNotEmpty() } ?: continue
                add(BrowserRecord(url = url, title = obj.optString("title", ""), timestampMillis = obj.optLong("ts", 0L)))
            }
        }
    }

    fun encodeDownloads(records: List<DownloadRecord>): String =
        JSONArray().apply {
            records.forEach { r ->
                put(
                    JSONObject().apply {
                        put("id", r.downloadManagerId)
                        put("url", r.url)
                        put("fileName", r.fileName)
                        put("ts", r.timestampMillis)
                    },
                )
            }
        }.toString()

    fun decodeDownloads(json: String?): List<DownloadRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                // Skip entries with no DownloadManager id, the same way
                // decodeRecords skips entries with no url. Beyond being junk,
                // these are actively dangerous downstream: the downloads list
                // keys its rows by this id, and two id-less entries would
                // collide on the same key and crash the list rather than just
                // rendering oddly.
                if (!obj.has("id") || obj.isNull("id")) continue
                add(
                    DownloadRecord(
                        downloadManagerId = obj.optLong("id", -1L),
                        url = obj.optString("url", ""),
                        fileName = obj.optString("fileName", ""),
                        timestampMillis = obj.optLong("ts", 0L),
                    ),
                )
            }
        }
    }
}
