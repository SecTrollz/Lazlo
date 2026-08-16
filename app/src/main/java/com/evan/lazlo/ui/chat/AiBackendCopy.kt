package com.evan.lazlo.ui.chat

import com.evan.lazlo.ai.LocalModelDownloader

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
            "Uses your own OpenRouter key to reach GPT-4o by default (or any other model OpenRouter routes to). " +
                "GPT-4o is a paid model — this needs a funded OpenRouter account (add credits at " +
                "openrouter.ai/credits), not just a key. A brand-new key with a \$0 balance will fail on send " +
                "with a payment-required error, not a Lazlo problem. Your messages go straight to OpenRouter " +
                "over the network — nothing routes through Lazlo's own servers, because there isn't one."
        AICORE_PROVIDER_ID ->
            "Runs fully offline on this device, using Android's built-in Gemini Nano model. Nothing you type ever leaves your phone. On the very first use, the device may need a minute to finish provisioning the model — that happens automatically, no action needed."
        MEDIAPIPE_PROVIDER_ID ->
            "Runs fully offline using a model file — either your own, or ${LocalModelDownloader.MODEL_DISPLAY_NAME}, " +
                "a small (~290MB) free download this app can fetch for you. No API key, no per-message cost. " +
                "Nothing you type ever leaves your phone."
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

    /**
     * Catches the single most common way a saved BYOK key ends up dead on
     * arrival: pasting the *other* provider's key into this dialog. Both
     * providers issue keys with a distinctive, stable prefix — Anthropic's
     * `sk-ant-`, OpenRouter's `sk-or-` — so a mismatch here is caught
     * before it ever reaches the network, instead of surfacing later as an
     * opaque `HTTP 401 authentication_error` from the wrong provider (the
     * key looks syntactically fine, so the request goes out and only the
     * server can tell it's wrong).
     *
     * Deliberately advisory, not a hard block: returns the warning text to
     * show, but callers still let the key be saved, since a nonstandard
     * key (an org proxy, a rotated format) shouldn't be locked out on a
     * heuristic that isn't in either provider's control.
     */
    fun keyFormatWarning(providerId: String, key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        return when (providerId) {
            API_KEY_PROVIDER_ID ->
                if (trimmed.startsWith("sk-or-")) {
                    "This looks like an OpenRouter key, not an Anthropic key — Anthropic keys start " +
                        "with sk-ant-. Add it under OpenRouter instead, or get an Anthropic key from " +
                        "console.anthropic.com."
                } else null
            OPENROUTER_PROVIDER_ID ->
                if (trimmed.startsWith("sk-ant-")) {
                    "This looks like an Anthropic key, not an OpenRouter key — OpenRouter keys start " +
                        "with sk-or-. Add it under Anthropic instead, or get an OpenRouter key from " +
                        "openrouter.ai/keys."
                } else null
            else -> null
        }
    }

    /**
     * Same advisory pattern as [keyFormatWarning], for the Hugging Face
     * access token [LocalModelDownloader] needs to fetch the offline
     * model — HF tokens have a stable, well-known `hf_` prefix, so a
     * pasted value missing it is almost certainly the wrong thing (a
     * copied model name, a stray space-separated word) rather than a
     * real token, and it's worth catching before a download attempt
     * burns a network round trip to find out.
     */
    fun huggingFaceTokenFormatWarning(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("hf_")) return null
        return "Hugging Face tokens start with hf_ — this doesn't look like one. Generate one at " +
            LocalModelDownloader.TOKEN_SETTINGS_URL + " after accepting the license at " +
            LocalModelDownloader.MODEL_INFO_URL + "."
    }
}
