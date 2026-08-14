package com.evan.lazlo.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.evan.lazlo.core.dataStore
import com.evan.lazlo.proxy.net.RewriteRule
import com.evan.lazlo.proxy.net.RewriteRuleCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Persists the user's rewrite rules — the "control" half of the
 * inspector, alongside the read-only traffic log. Same DataStore/JSON
 * pattern as everything else non-secret in this app (reuses the shared
 * delegate from `core/Settings.kt` rather than opening a second
 * DataStore instance for the same backing file).
 */
class RewriteRuleStore(private val context: Context) {

    private val keyRules = stringPreferencesKey("rewrite_rules")

    fun rules(): Flow<List<RewriteRule>> = context.dataStore.data.map { RewriteRuleCodec.decode(it[keyRules]) }

    /** Synchronous read for [com.evan.lazlo.proxy.net.TcpIpStack]'s rule-lookup callback, which runs on the packet pump's own coroutine, not tied to a ViewModel's collection. */
    fun rulesBlocking(): List<RewriteRule> = runBlocking { rules().first() }

    suspend fun addOrUpdate(rule: RewriteRule) {
        context.dataStore.edit { prefs ->
            val current = RewriteRuleCodec.decode(prefs[keyRules])
            val updated = if (current.any { it.id == rule.id }) {
                current.map { if (it.id == rule.id) rule else it }
            } else {
                current + rule
            }
            prefs[keyRules] = RewriteRuleCodec.encode(updated)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val current = RewriteRuleCodec.decode(prefs[keyRules])
            prefs[keyRules] = RewriteRuleCodec.encode(current.filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = RewriteRuleCodec.decode(prefs[keyRules])
            prefs[keyRules] = RewriteRuleCodec.encode(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
