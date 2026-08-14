package com.evan.lazlo.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evan.lazlo.browser.EngineKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "lazlo_settings")

/**
 * Non-secret preferences only. API keys and the CA private key never
 * pass through here — see SecretStore.
 */
class Settings(private val context: Context) {

    sealed class AiChoice {
        object ApiKey : AiChoice()
        object AiCore : AiChoice()
        data class LocalModel(val path: String) : AiChoice()
    }

    private val keyAiChoice = stringPreferencesKey("ai_choice")
    private val keyLocalModelPath = stringPreferencesKey("local_model_path")
    private val keyEngine = stringPreferencesKey("browser_engine")
    private val keyInspectorEnabled = stringPreferencesKey("inspector_enabled")

    suspend fun setAiChoice(choice: AiChoice) {
        context.dataStore.edit { prefs ->
            when (choice) {
                is AiChoice.ApiKey -> prefs[keyAiChoice] = "api_key"
                is AiChoice.AiCore -> prefs[keyAiChoice] = "aicore"
                is AiChoice.LocalModel -> {
                    prefs[keyAiChoice] = "local_model"
                    prefs[keyLocalModelPath] = choice.path
                }
            }
        }
    }

    suspend fun aiChoice(): AiChoice {
        val prefs = context.dataStore.data.first()
        return when (prefs[keyAiChoice]) {
            "aicore" -> AiChoice.AiCore
            "local_model" -> AiChoice.LocalModel(prefs[keyLocalModelPath] ?: "")
            else -> AiChoice.ApiKey
        }
    }

    /** Convenience for call sites (e.g. AiProviderFactory) not already in a coroutine. */
    fun aiChoiceBlocking(): AiChoice = runBlocking { aiChoice() }

    suspend fun setBrowserEngine(kind: EngineKind) {
        context.dataStore.edit { it[keyEngine] = kind.name }
    }

    suspend fun browserEngine(): EngineKind {
        val v = context.dataStore.data.first()[keyEngine]
        return runCatching { EngineKind.valueOf(v ?: "") }.getOrDefault(EngineKind.CHROMIUM)
    }

    suspend fun setInspectorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyInspectorEnabled] = enabled.toString() }
    }

    suspend fun inspectorEnabled(): Boolean =
        context.dataStore.data.first()[keyInspectorEnabled]?.toBoolean() ?: false
}
