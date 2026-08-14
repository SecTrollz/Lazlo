package com.evan.lazlo.ai

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** A single streamed piece of a model's reply. */
data class ChatToken(val text: String, val isFinal: Boolean = false)

/**
 * Common surface for every chat backend — BYOK API, AICore/Gemini Nano,
 * or a local MediaPipe model. The UI layer only ever depends on this.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val isOnDevice: Boolean

    /** Cheap readiness check (key present / model loaded / AICore available). */
    suspend fun isReady(): Boolean

    /** Streams the reply token-by-token; caller cancels the flow to abort. */
    fun streamChat(history: List<ChatMessage>): Flow<ChatToken>
}
