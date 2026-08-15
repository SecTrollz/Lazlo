package com.evan.lazlo.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure request/response shaping in [ApiKeyProvider.Config] —
 * the part of the OpenRouter/Anthropic wiring that doesn't need a real
 * network call to get wrong. `ApiKeyProvider` itself (the streaming
 * OkHttp path) isn't exercised here; this is the same "verify the shape
 * against the real request/response format" discipline the rest of this
 * codebase uses for external APIs.
 */
class ApiKeyProviderConfigTest {

    private val history = listOf(
        ChatMessage(ChatMessage.Role.USER, "hi"),
        ChatMessage(ChatMessage.Role.ASSISTANT, "hello"),
    )

    @Test
    fun `openRouter auth header is a bearer token`() {
        val config = ApiKeyProvider.openRouterDefault()
        val (name, value) = config.authHeader("sk-or-v1-test")
        assertEquals("Authorization", name)
        assertEquals("Bearer sk-or-v1-test", value)
    }

    @Test
    fun `openRouter request body is OpenAI-chat-completions shaped`() {
        val config = ApiKeyProvider.openRouterDefault(model = "openai/gpt-4o")
        val body = config.buildBody(history, config.model)
        assertEquals("openai/gpt-4o", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hi", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `openRouter request body caps max_tokens so a low-credit account can't be rejected before generating anything`() {
        // Confirmed against the real API: omitting this entirely, OpenRouter
        // defaults to the model's max output (16384 for gpt-4o) and returns
        // a 402 credit-limit error before a single token streams back, on
        // any account that can't cover that many tokens.
        val config = ApiKeyProvider.openRouterDefault()
        val body = config.buildBody(history, config.model)
        assertEquals(1024, body.getInt("max_tokens"))
    }

    @Test
    fun `openRouter parses an OpenAI-shaped streaming delta`() {
        val config = ApiKeyProvider.openRouterDefault()
        val chunk = JSONObject(
            """{"choices":[{"delta":{"content":"Hel"},"index":0}]}""",
        )
        assertEquals("Hel", config.parseSseDelta(chunk))
    }

    @Test
    fun `openRouter delta parsing tolerates a chunk with no content, e g the role-only opener`() {
        val config = ApiKeyProvider.openRouterDefault()
        val chunk = JSONObject("""{"choices":[{"delta":{"role":"assistant"},"index":0}]}""")
        assertNull(config.parseSseDelta(chunk))
    }

    @Test
    fun `anthropic config sends the required anthropic-version header`() {
        // /v1/messages rejects any request without it, so this isn't cosmetic:
        // omitting it makes every call on this backend fail before the model
        // ever sees the prompt.
        val config = ApiKeyProvider.anthropicDefault()
        assertEquals("2023-06-01", config.extraHeaders["anthropic-version"])
    }

    @Test
    fun `openRouter needs no extra headers beyond its bearer token`() {
        assertEquals(emptyMap<String, String>(), ApiKeyProvider.openRouterDefault().extraHeaders)
    }

    @Test
    fun `anthropic and openRouter configs use different ids so keys never collide`() {
        assertEquals("anthropic", ApiKeyProvider.anthropicDefault().id)
        assertEquals("openrouter", ApiKeyProvider.openRouterDefault().id)
    }
}
