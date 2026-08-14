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
        Settings.AiChoice.ApiKey -> ApiKeyProvider(
            secretStore = secretStore,
            config = ApiKeyProvider.anthropicDefault(),
        )
        Settings.AiChoice.AiCore -> AiCoreProvider(context)
        is Settings.AiChoice.LocalModel -> MediaPipeProvider(context, choice.path)
    }

    /** All backends currently usable on this device, for the Settings picker. */
    suspend fun availableProviders(localModelPath: String?): List<AiProvider> {
        val candidates = buildList {
            add(ApiKeyProvider(secretStore, ApiKeyProvider.anthropicDefault()))
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
        return candidates.filter { ready.getValue(it) || it.id == "anthropic" } // API key can be entered later
    }
}
