package com.evan.lazlo.ui.chat

import com.evan.lazlo.ai.ChatMessage
import com.evan.lazlo.ai.ChatToken

/**
 * Pure list-editing helpers for the chat transcript. Kept free of
 * ViewModel/Compose/Android so the "how do streamed tokens turn into
 * messages" logic is unit-testable on the JVM.
 */
object ChatTranscript {

    /** Appends a user message, exactly as typed. */
    fun appendUser(messages: List<ChatMessage>, text: String): List<ChatMessage> =
        messages + ChatMessage(ChatMessage.Role.USER, text)

    /** Appends an empty assistant placeholder that [applyToken] will fill in as tokens arrive. */
    fun startAssistantReply(messages: List<ChatMessage>): List<ChatMessage> =
        messages + ChatMessage(ChatMessage.Role.ASSISTANT, "")

    /**
     * Folds one streamed [token] onto the trailing assistant message. A
     * final, empty token (used by every provider to signal "done") is a
     * no-op here — it carries no text to append, only the isFinal flag,
     * which the caller uses to know when to stop treating the reply as
     * still-streaming.
     */
    fun applyToken(messages: List<ChatMessage>, token: ChatToken): List<ChatMessage> {
        if (token.text.isEmpty()) return messages
        val lastIndex = messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastIndex == -1) return messages
        val updated = messages[lastIndex].copy(content = messages[lastIndex].content + token.text)
        return messages.toMutableList().apply { this[lastIndex] = updated }
    }

    /** Drops a trailing assistant placeholder that never received any tokens (e.g. the stream failed immediately). */
    fun dropTrailingEmptyAssistant(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.lastOrNull() ?: return messages
        return if (last.role == ChatMessage.Role.ASSISTANT && last.content.isEmpty()) {
            messages.dropLast(1)
        } else {
            messages
        }
    }
}
