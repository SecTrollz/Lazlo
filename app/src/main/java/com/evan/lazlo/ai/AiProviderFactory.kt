package com.evan.lazlo.ai

import android.content.Context
import com.evan.lazlo.core.SecretStore
import com.evan.lazlo.core.Settings

/**
 * Single place that turns "what the user picked in Settings" into a
 * live AiProvider. The chat screen calls provider() once per session
 * and never branches on provider type itself.
 */
class AiProviderFactory(
    private val context: Context,
    private val settings: Settings,
    private val secretStore: SecretStore,
) {
    fun provider(): AiProvider = when (val choice = settings.aiChoiceBlocking()) {
        is Settings.AiChoice.ApiKey -> apiKeyProviderFor(choice.providerId)
        Settings.AiChoice.AiCore -> AiCoreProvider(context)
        is Settings.AiChoice.LocalModel -> MediaPipeProvider(context, choice.path)
    }

    private fun apiKeyProviderFor(providerId: String): ApiKeyProvider = ApiKeyProvider(
        secretStore = secretStore,
        config = if (providerId == "openrouter") ApiKeyProvider.openRouterDefault() else ApiKeyProvider.anthropicDefault(),
    )

    /** All backends currently usable on this device, for the Settings picker. */
    suspend fun availableProviders(localModelPath: String?): List<AiProvider> {
        val candidates = buildList {
            add(ApiKeyProvider(secretStore, ApiKeyProvider.anthropicDefault()))
            add(ApiKeyProvider(secretStore, ApiKeyProvider.openRouterDefault()))
            add(AiCoreProvider(context))
            if (localModelPath != null) add(MediaPipeProvider(context, localModelPath))
        }
        val ready = candidates.associateWith { it.isReady() }
        // These candidates exist only to answer "is this backend usable
        // right now" — an actual chat session always asks provider() for
        // a fresh instance instead of reusing one of these. Without this,
        // every call here would silently leave an on-device model
        // (AiCoreProvider's GenerativeModel, MediaPipeProvider's
        // LlmInference) loaded and never released. Both providers reload
        // lazily on their next isReady()/streamChat() call, so closing
        // them here doesn't make them any less usable afterwards.
        candidates.forEach { it.close() }
        // Every BYOK backend is always offered regardless of isReady(),
        // since a key can always be entered later — checked by type, not
        // a hardcoded id, so this doesn't need updating for the next one.
        return candidates.filter { ready.getValue(it) || it is ApiKeyProvider }
    }
}
