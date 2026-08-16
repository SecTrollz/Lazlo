package com.evan.lazlo.ai

/**
 * Turns MediaPipe's raw `(partialText, done)` progress-listener callback
 * pairs into the [ChatToken] stream [MediaPipeProvider] emits. Pulled out
 * of [MediaPipeProvider] so this decision logic is unit-testable without
 * a real `LlmInference` engine or an Android `Context`.
 *
 * **Why this exists**: decompiling the real `tasks-genai:0.10.35` AAR
 * (`LlmInferenceSession.generateResponseAsync()`'s inner
 * `Consumer<LlmResponseContext>`) shows MediaPipe never itself treats a
 * reply with zero generated characters as a failure — its `accept()`
 * always resolves the completion future/callback successfully once
 * `responseContext.getDone()` is true, regardless of whether any real
 * text was ever produced:
 * ```
 * public void accept(LlmResponseContext responseContext) {
 *     boolean done = responseContext.getDone();
 *     String partialResultDecoded = decodeResponse(responseContext.getResponsesList(), ...);
 *     this.response.append(partialResultDecoded);
 *     if (done) { ...; future.set(this.response.toString()); }
 *     progressListener.run(partialResultDecoded, done);
 * }
 * ```
 * Left alone, that means a totally empty generation — the model emits
 * nothing but whitespace/EOS before any real token, which decodeResponse
 * (also decompiled) reduces to `""` — surfaces as a *successful*,
 * permanently blank assistant bubble with no error at all: exactly the
 * "…" placeholder bug (`ChatScreen.kt`'s `message.content.ifEmpty { "…" }`)
 * this class exists to catch. [onProgress] tracks whether any non-empty
 * chunk has ever arrived; if `done` shows up having never seen one, it
 * hands back [Action.Fail] instead of a silent empty completion.
 *
 * The `partial` text handed to `progressListener.run` is confirmed (same
 * decompiled `accept()`) to be the callback's own newly-decoded chunk —
 * not the accumulated `this.response` — so it's already the incremental
 * delta this class (and [MediaPipeProvider], and [ChatTranscript.applyToken])
 * expects; no cumulative-vs-incremental adaptation is needed here.
 */
class MediaPipeResponseAccumulator {

    private var receivedText = false

    sealed interface Action {
        /** Forward [token] to the flow; when [token].isFinal, the flow should close normally after. */
        data class Emit(val token: ChatToken) : Action

        /** Close the flow with [error] instead of completing normally — no real text ever arrived. */
        data class Fail(val error: Throwable) : Action
    }

    /** Call once per MediaPipe progress-listener invocation, in order. */
    fun onProgress(partial: String, done: Boolean): Action {
        if (partial.isNotEmpty()) receivedText = true
        return if (done && !receivedText) {
            Action.Fail(
                IllegalStateException(
                    "The local model produced no text for this reply. This can happen when the " +
                        "conversation has grown past the model's token budget (prompt + reply share a " +
                        "single fixed budget for this backend), or from an unstable generation for this " +
                        "particular prompt — try a shorter message, or clear the conversation and try again.",
                ),
            )
        } else {
            Action.Emit(ChatToken(partial, isFinal = done))
        }
    }
}
