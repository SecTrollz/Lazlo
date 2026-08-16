package com.evan.lazlo.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaPromptFormatTest {

    @Test
    fun `a single user turn wraps in start_of_turn user and opens a model turn`() {
        val history = listOf(ChatMessage(ChatMessage.Role.USER, "hello"))
        assertEquals(
            "<start_of_turn>user\nhello<end_of_turn>\n<start_of_turn>model\n",
            GemmaPromptFormat.build(history),
        )
    }

    @Test
    fun `multiple turns concatenate in order with alternating roles`() {
        val history = listOf(
            ChatMessage(ChatMessage.Role.USER, "knock knock"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "who is there"),
            ChatMessage(ChatMessage.Role.USER, "Gemma"),
        )
        assertEquals(
            "<start_of_turn>user\nknock knock<end_of_turn>\n" +
                "<start_of_turn>model\nwho is there<end_of_turn>\n" +
                "<start_of_turn>user\nGemma<end_of_turn>\n" +
                "<start_of_turn>model\n",
            GemmaPromptFormat.build(history),
        )
    }

    @Test
    fun `a system message folds into the first user turn instead of getting its own turn`() {
        val history = listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, "Be concise."),
            ChatMessage(ChatMessage.Role.USER, "hi"),
        )
        val prompt = GemmaPromptFormat.build(history)
        assertEquals(
            "<start_of_turn>user\nBe concise.\n\nhi<end_of_turn>\n<start_of_turn>model\n",
            prompt,
        )
        // Gemma's template has no system-turn concept at all.
        assert(!prompt.contains("system"))
    }

    @Test
    fun `empty history still opens a model turn rather than producing a blank string`() {
        assertEquals("<start_of_turn>model\n", GemmaPromptFormat.build(emptyList()))
    }

    @Test
    fun `a history longer than maxTurns keeps only the most recent turns`() {
        // 5 user/assistant exchanges (10 messages) trimmed to the last 2 turns (4 messages).
        val history = (1..5).flatMap { i ->
            listOf(
                ChatMessage(ChatMessage.Role.USER, "user-$i"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "assistant-$i"),
            )
        }
        val prompt = GemmaPromptFormat.build(history, maxTurns = 2)
        assertEquals(
            "<start_of_turn>user\nuser-4<end_of_turn>\n" +
                "<start_of_turn>model\nassistant-4<end_of_turn>\n" +
                "<start_of_turn>user\nuser-5<end_of_turn>\n" +
                "<start_of_turn>model\nassistant-5<end_of_turn>\n" +
                "<start_of_turn>model\n",
            prompt,
        )
        assert(!prompt.contains("user-1") && !prompt.contains("user-3"))
    }

    @Test
    fun `a history shorter than maxTurns is left untrimmed`() {
        val history = listOf(
            ChatMessage(ChatMessage.Role.USER, "one"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "two"),
        )
        assertEquals(GemmaPromptFormat.build(history), GemmaPromptFormat.build(history, maxTurns = 6))
    }

    @Test
    fun `a system message survives trimming and still folds into the first kept user turn`() {
        val history = listOf(ChatMessage(ChatMessage.Role.SYSTEM, "Be concise.")) +
            (1..5).flatMap { i ->
                listOf(
                    ChatMessage(ChatMessage.Role.USER, "user-$i"),
                    ChatMessage(ChatMessage.Role.ASSISTANT, "assistant-$i"),
                )
            }
        val prompt = GemmaPromptFormat.build(history, maxTurns = 1)
        assertEquals(
            "<start_of_turn>user\nBe concise.\n\nuser-5<end_of_turn>\n" +
                "<start_of_turn>model\nassistant-5<end_of_turn>\n" +
                "<start_of_turn>model\n",
            prompt,
        )
    }
}
