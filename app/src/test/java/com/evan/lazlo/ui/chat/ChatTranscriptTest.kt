package com.evan.lazlo.ui.chat

import com.evan.lazlo.ai.ChatMessage
import com.evan.lazlo.ai.ChatToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {

    @Test
    fun `appendUser adds a user message`() {
        val result = ChatTranscript.appendUser(emptyList(), "hi")
        assertEquals(listOf(ChatMessage(ChatMessage.Role.USER, "hi")), result)
    }

    @Test
    fun `startAssistantReply appends an empty assistant placeholder`() {
        val messages = ChatTranscript.appendUser(emptyList(), "hi")
        val result = ChatTranscript.startAssistantReply(messages)
        assertEquals(2, result.size)
        assertEquals(ChatMessage.Role.ASSISTANT, result[1].role)
        assertEquals("", result[1].content)
    }

    @Test
    fun `applyToken appends text onto the trailing assistant message`() {
        var messages = ChatTranscript.appendUser(emptyList(), "hi")
        messages = ChatTranscript.startAssistantReply(messages)
        messages = ChatTranscript.applyToken(messages, ChatToken("Hel"))
        messages = ChatTranscript.applyToken(messages, ChatToken("lo"))
        assertEquals("Hello", messages.last().content)
        // the user message must be untouched
        assertEquals("hi", messages.first().content)
    }

    @Test
    fun `applyToken with an empty final token does not append anything`() {
        var messages = ChatTranscript.appendUser(emptyList(), "hi")
        messages = ChatTranscript.startAssistantReply(messages)
        messages = ChatTranscript.applyToken(messages, ChatToken("hey"))
        val beforeFinal = messages
        messages = ChatTranscript.applyToken(messages, ChatToken("", isFinal = true))
        assertEquals(beforeFinal, messages)
    }

    @Test
    fun `applyToken on an empty transcript is a no-op`() {
        val result = ChatTranscript.applyToken(emptyList(), ChatToken("x"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `dropTrailingEmptyAssistant removes an unfilled placeholder`() {
        var messages = ChatTranscript.appendUser(emptyList(), "hi")
        messages = ChatTranscript.startAssistantReply(messages)
        val result = ChatTranscript.dropTrailingEmptyAssistant(messages)
        assertEquals(1, result.size)
        assertEquals(ChatMessage.Role.USER, result[0].role)
    }

    @Test
    fun `dropTrailingEmptyAssistant leaves a filled reply alone`() {
        var messages = ChatTranscript.appendUser(emptyList(), "hi")
        messages = ChatTranscript.startAssistantReply(messages)
        messages = ChatTranscript.applyToken(messages, ChatToken("hello"))
        val result = ChatTranscript.dropTrailingEmptyAssistant(messages)
        assertEquals(2, result.size)
    }

    @Test
    fun `dropTrailingEmptyAssistant on an empty list is a no-op`() {
        assertTrue(ChatTranscript.dropTrailingEmptyAssistant(emptyList()).isEmpty())
    }
}
