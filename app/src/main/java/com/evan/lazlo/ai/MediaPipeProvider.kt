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

    // The MediaPipe API takes its result listener once, at engine
    // creation, rather than per call — so a single shared listener
    // forwards to whichever streamChat() collector is currently active.
    // Fine for this app's one-conversation-at-a-time chat UI; a second
    // concurrent stream would need a request-queue instead.
    private var activeListener: ((partial: String, done: Boolean) -> Unit)? = null

    override suspend fun isReady(): Boolean = runCatching {
        if (File(modelPath).exists() && engine == null) {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setResultListener { partial, done -> activeListener?.invoke(partial, done) }
                .build()
            engine = LlmInference.createFromOptions(context, options)
        }
        engine != null
    }.getOrDefault(false)

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = callbackFlow {
        val e = engine ?: run { isReady(); engine }
        ?: throw IllegalStateException("Local model not loaded: $modelPath")

        val prompt = history.joinToString("\n\n") { "${it.role}: ${it.content}" }
        activeListener = { partial, done ->
            trySend(ChatToken(partial, isFinal = done))
            if (done) close()
        }
        e.generateResponseAsync(prompt)
        awaitClose { activeListener = null }
    }

    /** Frees the native inference engine backing [engine]; safe to call even if a model was never loaded. */
    override fun close() {
        engine?.close()
        engine = null
    }
}
