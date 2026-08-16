package com.evan.lazlo.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Runs a local model file — either user-supplied, or one
 * [LocalModelDownloader] fetched — fully offline via the MediaPipe LLM
 * Inference API. Model path is chosen by the user in Settings; nothing
 * beyond that is bundled or downloaded automatically from this class
 * itself.
 */
class MediaPipeProvider(
    private val context: Context,
    private val modelPath: String,
) : AiProvider {

    override val id = "mediapipe-local"
    override val displayName = "Local model (${File(modelPath).name})"
    override val isOnDevice = true

    private var engine: LlmInference? = null

    // Whatever LlmInference.createFromOptions actually threw the last
    // time isReady() tried to load the model — a corrupt/incompatible
    // .task bundle, a native/OOM failure, anything. isReady() itself
    // stays a plain Boolean (AiProvider's contract, and the picker's
    // checkmark only needs true/false), but streamChat() surfacing a bare
    // "not loaded" with the real reason thrown away is a dead end: the
    // whole point of a local-model download flow is that a bad download
    // or an incompatible file needs to be diagnosable from the chat
    // screen, not just "try again and hope."
    private var loadError: Throwable? = null

    override suspend fun isReady(): Boolean = runCatching {
        if (File(modelPath).exists() && engine == null) {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()
            engine = LlmInference.createFromOptions(context, options)
            loadError = null
        }
        engine != null
    }.onFailure { t -> loadError = t }.getOrDefault(false)

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = callbackFlow {
        val e = engine ?: run { isReady(); engine }
        ?: throw IllegalStateException(
            when {
                loadError != null -> "Couldn't load the local model ($modelPath): ${loadError?.message ?: loadError}"
                !File(modelPath).exists() -> "Local model file is missing: $modelPath"
                else -> "Local model not loaded: $modelPath"
            },
            loadError,
        )

        val prompt = GemmaPromptFormat.build(history)
        // The result listener is a per-call parameter on generateResponseAsync
        // in this tasks-genai version, not a one-time builder option the way
        // LlmInferenceOptions.setResultListener() used to work — so each
        // streamChat() call wires its own listener straight to this flow's
        // trySend/close, no shared "which collector is active" indirection
        // needed the way a build-time listener would have required.
        //
        // MediaPipeResponseAccumulator turns each (partial, done) pair into
        // either a token to forward or, if generation finished having never
        // produced a single real character, a failure — MediaPipe's own
        // accept() (verified against the decompiled 0.10.35 AAR) resolves
        // that case as a normal success, which otherwise silently leaves a
        // permanently blank "…" assistant bubble with no error at all.
        val accumulator = MediaPipeResponseAccumulator()
        e.generateResponseAsync(prompt) { partial, done ->
            when (val action = accumulator.onProgress(partial, done)) {
                is MediaPipeResponseAccumulator.Action.Emit -> {
                    trySend(action.token)
                    if (done) close()
                }
                is MediaPipeResponseAccumulator.Action.Fail -> close(action.error)
            }
        }
        awaitClose {}
    }

    /** Frees the native inference engine backing [engine]; safe to call even if a model was never loaded. */
    override fun close() {
        engine?.close()
        engine = null
    }
}
