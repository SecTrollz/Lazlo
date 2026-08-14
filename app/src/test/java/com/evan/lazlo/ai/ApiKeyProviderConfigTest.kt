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
    fun `anthropic and openRouter configs use different ids so keys never collide`() {
        assertEquals("anthropic", ApiKeyProvider.anthropicDefault().id)
        assertEquals("openrouter", ApiKeyProvider.openRouterDefault().id)
    }
}
