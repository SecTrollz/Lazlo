package com.evan.lazlo.browser

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.evan.lazlo.core.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Persists the user's browser scripts — same DataStore/JSON pattern as
 * [com.evan.lazlo.proxy.RewriteRuleStore], reusing the shared delegate
 * from `core/Settings.kt` rather than opening a second DataStore instance
 * for the same backing file.
 */
class BrowserScriptStore(private val context: Context) {

    private val keyScripts = stringPreferencesKey("browser_scripts")

    fun scripts(): Flow<List<BrowserScript>> = context.dataStore.data.map { BrowserScriptCodec.decode(it[keyScripts]) }

    suspend fun addOrUpdate(script: BrowserScript) {
        context.dataStore.edit { prefs ->
            val current = BrowserScriptCodec.decode(prefs[keyScripts])
            val updated = if (current.any { it.id == script.id }) {
                current.map { if (it.id == script.id) script else it }
            } else {
                current + script
            }
            prefs[keyScripts] = BrowserScriptCodec.encode(updated)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val current = BrowserScriptCodec.decode(prefs[keyScripts])
            prefs[keyScripts] = BrowserScriptCodec.encode(current.filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = BrowserScriptCodec.decode(prefs[keyScripts])
            prefs[keyScripts] = BrowserScriptCodec.encode(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
