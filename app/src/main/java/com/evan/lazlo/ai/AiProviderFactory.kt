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
        return candidates.filter { it.isReady() || it.id == "anthropic" } // API key can be entered later
    }
}
