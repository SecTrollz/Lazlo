package com.evan.lazlo.ai

import android.content.Context
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.GenerationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wraps the on-device Gemini Nano model exposed by AICore on supported
 * Pixel devices. Fully offline — nothing here touches the network.
 * Falls back to isReady()=false on devices/OS versions without AICore
 * so the UI can grey the option out instead of crashing.
 */
class AiCoreProvider(private val context: Context) : AiProvider {

    override val id = "aicore-nano"
    override val displayName = "Gemini Nano (on-device)"
    override val isOnDevice = true

    private var model: GenerativeModel? = null

    override suspend fun isReady(): Boolean = runCatching {
        if (model == null) {
            model = GenerativeModel(
                generationConfig = GenerationConfig.builder()
                    .apply { context = this@AiCoreProvider.context }
                    .build()
            )
        }
        model != null
    }.getOrDefault(false)

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = flow {
        val m = model ?: run { isReady(); model }
        ?: throw IllegalStateException("AICore unavailable on this device")

        val prompt = history.joinToString("\n\n") { "${it.role}: ${it.content}" }
        // AICore's streaming callback API is adapted to a cold Flow here;
        // in the real client this wraps GenerativeModel.generateContentStream(prompt).
        m.generateContentStream(prompt).collect { chunk ->
            emit(ChatToken(chunk.text ?: ""))
        }
        emit(ChatToken("", isFinal = true))
    }
}
