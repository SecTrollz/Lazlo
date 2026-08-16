package com.evan.lazlo.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evan.lazlo.browser.EngineKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

// internal, not private: a DataStore-backed named file must only ever be
// created by one `preferencesDataStore` delegate per process (a second
// delegate for the same name throws "multiple DataStores active for the
// same file" at runtime) — BrowserDataStore reuses this same instance
// rather than declaring its own.
internal val Context.dataStore by preferencesDataStore(name = "lazlo_settings")

/**
 * Non-secret preferences only. API keys and the CA private key never
 * pass through here — see SecretStore.
 */
class Settings(private val context: Context) {

    sealed class AiChoice {
        /** [providerId] picks which BYOK backend is active — "anthropic" or "openrouter" — each with its own stored key. */
        data class ApiKey(val providerId: String = "anthropic") : AiChoice()
        object AiCore : AiChoice()
        data class LocalModel(val path: String) : AiChoice()
    }

    private val keyAiChoice = stringPreferencesKey("ai_choice")
    private val keyApiKeyProviderId = stringPreferencesKey("api_key_provider_id")
    private val keyLocalModelPath = stringPreferencesKey("local_model_path")
    private val keyEngine = stringPreferencesKey("browser_engine")
    private val keyInspectorEnabled = stringPreferencesKey("inspector_enabled")
    private val keyScreenshotProtection = stringPreferencesKey("screenshot_protection_enabled")
    private val keyPillPrivacy = stringPreferencesKey("inspector_pill_privacy")

    suspend fun setAiChoice(choice: AiChoice) {
        context.dataStore.edit { prefs ->
            when (choice) {
                is AiChoice.ApiKey -> {
                    prefs[keyAiChoice] = "api_key"
                    prefs[keyApiKeyProviderId] = choice.providerId
                }
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
            // Missing keyApiKeyProviderId means an install from before
            // OpenRouter existed — defaults to the original Anthropic
            // backend, same as before this key existed.
            else -> AiChoice.ApiKey(prefs[keyApiKeyProviderId] ?: "anthropic")
        }
    }

    /** Convenience for call sites (e.g. AiProviderFactory) not already in a coroutine. */
    fun aiChoiceBlocking(): AiChoice = runBlocking { aiChoice() }

    /**
     * True once [setAiChoice] has ever been called — distinct from
     * [aiChoice] itself, which always returns *something* (falling back
     * to the Anthropic BYOK row) even when nothing's actually been
     * picked. ChatViewModel uses this to tell "fresh install, auto-pick
     * the best zero-setup backend" apart from "user genuinely chose BYOK".
     */
    suspend fun hasChosenAiBackend(): Boolean =
        context.dataStore.data.first().contains(keyAiChoice)

    suspend fun setBrowserEngine(kind: EngineKind) {
        context.dataStore.edit { it[keyEngine] = kind.name }
    }

    suspend fun browserEngine(): EngineKind {
        val v = context.dataStore.data.first()[keyEngine]
        // GeckoView, not the system WebView, is the default: it doesn't
        // carry WebView's embedded-browser fingerprint (the "; wv)" UA
        // token and, on newer builds, an "Android WebView" User-Agent
        // Client Hints brand — see ChromiumEngine.hardenFingerprint for
        // where those get stripped when Chromium's picked instead) in the
        // first place. BrowserViewModel handles the case where nothing's
        // been picked yet and the Gecko module hasn't downloaded — it
        // fetches it automatically instead of silently settling for
        // WebView.
        return runCatching { EngineKind.valueOf(v ?: "") }.getOrDefault(EngineKind.GECKO)
    }

    suspend fun setInspectorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyInspectorEnabled] = enabled.toString() }
    }

    suspend fun inspectorEnabled(): Boolean =
        context.dataStore.data.first()[keyInspectorEnabled]?.toBoolean() ?: false

    suspend fun setScreenshotProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyScreenshotProtection] = enabled.toString() }
    }

    /**
     * Defaults to on (screenshots/screen recording/Recents-thumbnail
     * capture blocked) since that's the safer default for anyone
     * carrying chat history or captured traffic on this device — but
     * it's a real toggle, not a hard lock: a power user who needs to
     * screenshot or record their own findings (a repro, a report) can
     * turn it off. [MainActivity] observes [screenshotProtectionFlow] to
     * apply this live, without needing to reopen the app.
     */
    fun screenshotProtectionFlow(): Flow<Boolean> =
        context.dataStore.data.map { it[keyScreenshotProtection]?.toBoolean() ?: true }

    suspend fun setPillPrivacyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyPillPrivacy] = enabled.toString() }
    }

    /**
     * Off by default — the pill's numbers (capture count, recent hosts)
     * show plainly until turned on. [InspectorPillOverlay] masks them to
     * "••" while this is on, for glancing at the pill's status without
     * putting live counts/hostnames on screen (a call, screen share,
     * over someone's shoulder).
     */
    fun pillPrivacyFlow(): Flow<Boolean> =
        context.dataStore.data.map { it[keyPillPrivacy]?.toBoolean() ?: false }
}
