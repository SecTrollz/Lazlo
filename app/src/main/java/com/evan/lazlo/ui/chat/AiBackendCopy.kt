package com.evan.lazlo.ui.chat

/**
 * Plain-language, one-line explanations of each AI backend, keyed by
 * [com.evan.lazlo.ai.AiProvider.id]. Separated out as pure functions so
 * the copy — the exact thing a first-time user reads to decide which
 * backend to pick — is unit-testable and has one home instead of being
 * scattered across Composables.
 */
object AiBackendCopy {

    const val API_KEY_PROVIDER_ID = "anthropic"
    const val OPENROUTER_PROVIDER_ID = "openrouter"
    const val AICORE_PROVIDER_ID = "aicore-nano"
    const val MEDIAPIPE_PROVIDER_ID = "mediapipe-local"

    fun explanation(providerId: String): String = when (providerId) {
        API_KEY_PROVIDER_ID ->
            "Uses your own API key. Your messages go straight to the provider you choose over the network — nothing routes through Lazlo's own servers, because there isn't one."
        OPENROUTER_PROVIDER_ID ->
            "Uses your own OpenRouter key to reach GPT-4o (or any other model OpenRouter routes to). Your messages go straight to OpenRouter over the network — nothing routes through Lazlo's own servers, because there isn't one."
        AICORE_PROVIDER_ID ->
            "Runs fully offline on this device, using Android's built-in Gemini Nano model. Nothing you type ever leaves your phone. On the very first use, the device may need a minute to finish provisioning the model — that happens automatically, no action needed."
        MEDIAPIPE_PROVIDER_ID ->
            "Runs fully offline using a model file you supply. Nothing you type ever leaves your phone."
        else -> "A chat backend."
    }

    fun shortLabel(providerId: String): String = when (providerId) {
        API_KEY_PROVIDER_ID -> "Your own API key (Anthropic)"
        OPENROUTER_PROVIDER_ID -> "Your own API key (OpenRouter)"
        AICORE_PROVIDER_ID -> "On-device (Gemini Nano)"
        MEDIAPIPE_PROVIDER_ID -> "On-device (local model file)"
        else -> providerId
    }

    /** Just the service name — for spots like the API key dialog title that already say "API key" themselves. */
    fun serviceName(providerId: String): String = when (providerId) {
        API_KEY_PROVIDER_ID -> "Anthropic"
        OPENROUTER_PROVIDER_ID -> "OpenRouter"
        else -> providerId
    }
}
