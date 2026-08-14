package com.evan.lazlo.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Runs a user-supplied local model file (e.g. a MediaPipe-converted
 * Gemma checkpoint) fully offline via the MediaPipe LLM Inference API.
 * Model path is chosen by the user in Settings — nothing is bundled
 * or downloaded automatically.
 */
class MediaPipeProvider(
    private val context: Context,
    private val modelPath: String,
) : AiProvider {

    override val id = "mediapipe-local"
    override val displayName = "Local model (${File(modelPath).name})"
    override val isOnDevice = true

    private var engine: LlmInference? = null

    override suspend fun isReady(): Boolean = runCatching {
        if (File(modelPath).exists() && engine == null) {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()
            engine = LlmInference.createFromOptions(context, options)
        }
        engine != null
    }.getOrDefault(false)

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = callbackFlow {
        val e = engine ?: run { isReady(); engine }
        ?: throw IllegalStateException("Local model not loaded: $modelPath")

        val prompt = history.joinToString("\n\n") { "${it.role}: ${it.content}" }
        e.generateResponseAsync(prompt) { partial, done ->
            trySend(ChatToken(partial, isFinal = done))
            if (done) close()
        }
        awaitClose { /* engine session cleanup if the API exposes cancel() */ }
    }
}
