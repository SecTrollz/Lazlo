package com.evan.lazlo.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * History, bookmarks, and the local download record for Lazlo's Browser
 * tab — non-secret, so it lives in the same DataStore [Settings] uses
 * (JSON-encoded lists via [BrowserRecordCodec], same pattern as every
 * other non-secret preference in this app). Nothing here ever leaves the
 * device; there's no sync, no account, no first-party backend to sync to.
 */
class BrowserDataStore(private val context: Context) {

    private val keyHistory = stringPreferencesKey("browser_history")
    private val keyBookmarks = stringPreferencesKey("browser_bookmarks")
    private val keyDownloads = stringPreferencesKey("browser_downloads")

    fun history(): Flow<List<BrowserRecord>> =
        context.dataStore.data.map { BrowserRecordCodec.decodeRecords(it[keyHistory]) }

    /** Most-recent-first, de-duplicated by URL (a repeat visit moves to the front instead of adding a second entry). */
    suspend fun recordVisit(url: String, title: String) {
        if (url.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = BrowserRecordCodec.decodeRecords(prefs[keyHistory])
            val updated = (listOf(BrowserRecord(url, title, System.currentTimeMillis())) + current.filterNot { it.url == url })
                .take(MAX_HISTORY)
            prefs[keyHistory] = BrowserRecordCodec.encodeRecords(updated)
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(keyHistory) }
    }

    fun bookmarks(): Flow<List<BrowserRecord>> =
        context.dataStore.data.map { BrowserRecordCodec.decodeRecords(it[keyBookmarks]) }

    /** Adds [url] if it isn't already bookmarked, removes it if it is. Returns the new bookmarked state. */
    suspend fun toggleBookmark(url: String, title: String): Boolean {
        var nowBookmarked = false
        context.dataStore.edit { prefs ->
            val current = BrowserRecordCodec.decodeRecords(prefs[keyBookmarks])
            val alreadyBookmarked = current.any { it.url == url }
            nowBookmarked = !alreadyBookmarked
            val updated = if (alreadyBookmarked) {
                current.filterNot { it.url == url }
            } else {
                listOf(BrowserRecord(url, title, System.currentTimeMillis())) + current
            }
            prefs[keyBookmarks] = BrowserRecordCodec.encodeRecords(updated)
        }
        return nowBookmarked
    }

    fun downloads(): Flow<List<DownloadRecord>> =
        context.dataStore.data.map { BrowserRecordCodec.decodeDownloads(it[keyDownloads]) }

    suspend fun recordDownload(record: DownloadRecord) {
        context.dataStore.edit { prefs ->
            val current = BrowserRecordCodec.decodeDownloads(prefs[keyDownloads])
            prefs[keyDownloads] = BrowserRecordCodec.encodeDownloads((listOf(record) + current).take(MAX_DOWNLOADS))
        }
    }

    private companion object {
        const val MAX_HISTORY = 300
        const val MAX_DOWNLOADS = 100
    }
}
