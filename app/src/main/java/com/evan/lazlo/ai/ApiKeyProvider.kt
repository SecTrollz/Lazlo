package com.evan.lazlo.ai

import com.evan.lazlo.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Generic bring-your-own-key streaming client. Config (base URL, model
 * name, header format) is injected so this works against any
 * Anthropic-/OpenAI-shaped streaming endpoint without code changes.
 */
class ApiKeyProvider(
    private val secretStore: SecretStore,
    private val config: Config,
    private val client: OkHttpClient = OkHttpClient(),
) : AiProvider {

    data class Config(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val model: String,
        val authHeader: (key: String) -> Pair<String, String>,
        val buildBody: (List<ChatMessage>, model: String) -> JSONObject,
        val parseSseDelta: (JSONObject) -> String?,
    )

    override val id get() = config.id
    override val displayName get() = config.displayName
    override val isOnDevice = false

    override suspend fun isReady(): Boolean = secretStore.getApiKey(config.id) != null

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = callbackFlow {
        val key = secretStore.getApiKey(config.id)
            ?: throw IllegalStateException("No API key stored for ${config.id}")

        val (headerName, headerValue) = config.authHeader(key)
        val body = config.buildBody(history, config.model).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(config.baseUrl)
            .addHeader(headerName, headerValue)
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        // Otherwise a bad key, a rate limit, or a malformed
                        // request would silently look like an empty reply
                        // instead of surfacing as the error it is — the
                        // body often has a JSON error payload, but even a
                        // truncated read of it beats no message at all.
                        val detail = runCatching { it.body?.string() }.getOrNull()?.take(500)
                        close(IOException("HTTP ${it.code} ${it.message}".trim() + (detail?.let { d -> ": $d" } ?: "")))
                        return
                    }
                    val source: BufferedSource? = it.body?.source()
                    while (source != null && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") { trySend(ChatToken("", isFinal = true)); continue }
                        runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                            config.parseSseDelta(json)?.let { delta -> trySend(ChatToken(delta)) }
                        }
                    }
                    trySend(ChatToken("", isFinal = true))
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Ready-made config for Anthropic-shaped /v1/messages streaming. */
        fun anthropicDefault(model: String = "claude-sonnet-4-6") = Config(
            id = "anthropic",
            displayName = "Anthropic API",
            baseUrl = "https://api.anthropic.com/v1/messages",
            model = model,
            authHeader = { key -> "x-api-key" to key },
            buildBody = { history, model ->
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("stream", true)
                    put("messages", JSONArray(history.filter { it.role != ChatMessage.Role.SYSTEM }
                        .map { m ->
                            JSONObject().apply {
                                put("role", if (m.role == ChatMessage.Role.USER) "user" else "assistant")
                                put("content", m.content)
                            }
                        }))
                }
            },
            parseSseDelta = { json ->
                json.optJSONObject("delta")?.optString("text", null)
            },
        )
    }
}
