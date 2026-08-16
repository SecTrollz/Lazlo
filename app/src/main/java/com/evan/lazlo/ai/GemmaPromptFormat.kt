package com.evan.lazlo.ai

/**
 * Gemma's instruction-tuned chat template. Required because MediaPipe's
 * `LlmInference.generateResponseAsync()` is a stateless, raw-text
 * completion API — it does not apply any chat structure to the prompt
 * string on its own, so [MediaPipeProvider] handing it a plain
 * `"USER: hello\n\nASSISTANT:"`-style concatenation isn't the format the
 * `-it` checkpoint was actually instruction-tuned on. A prompt shape the
 * model was never fine-tuned to expect is exactly the kind of thing that
 * produces an empty or near-empty completion instead of a real reply —
 * the model isn't broken, it's just never seen this shape of input mean
 * "now respond."
 *
 * Verified against Gemma's own documented prompt format
 * (ai.google.dev/gemma/docs/core/prompt-structure): turns wrapped in
 * `<start_of_turn>{user|model}\n...<end_of_turn>`, concatenated in
 * order, ending with an *open* `<start_of_turn>model` turn for the
 * completion to fill in. Gemma's template has no system-turn concept at
 * all — its own docs say to fold system-level instructions into the
 * first user turn instead, which is what [build] does here.
 *
 * **Turn budget**: [MediaPipeProvider] resends this whole conversation
 * as one prompt on every message — MediaPipe's LLM Inference API has no
 * server-side session memory the way a hosted chat API does — and
 * `LlmInferenceOptions.maxTokens` is a single fixed budget covering
 * *prompt and reply combined* (confirmed against the decompiled
 * `tasks-genai:0.10.35` AAR: there's no separate output-length field
 * anywhere in the generation config). An unbounded, ever-growing history
 * against a fixed combined budget means every conversation eventually
 * hits it — not a rare edge case, just a matter of how many turns in.
 * [build] defaults to only the most recent [maxTurns] non-system
 * messages so the prompt itself stays a roughly constant size regardless
 * of how long the conversation has actually run, trading older context
 * for headroom the reply actually needs. The system preamble, if any, is
 * exempt from this trim — it's one message, not one per turn — and
 * always folds into whichever user turn ends up first in the kept window.
 */
object GemmaPromptFormat {

    /** Kept turns per side — 6 means "up to the last 6 user messages and 6 assistant replies," not 6 messages total. */
    private const val DEFAULT_MAX_TURNS = 6

    fun build(history: List<ChatMessage>, maxTurns: Int = DEFAULT_MAX_TURNS): String {
        val systemPreamble = history
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }
        val turns = history.filterNot { it.role == ChatMessage.Role.SYSTEM }.takeLast(maxTurns * 2)
        var systemFolded = false

        return buildString {
            turns.forEach { message ->
                val role = if (message.role == ChatMessage.Role.USER) "user" else "model"
                append("<start_of_turn>").append(role).append('\n')
                if (!systemFolded && systemPreamble != null && message.role == ChatMessage.Role.USER) {
                    append(systemPreamble).append("\n\n")
                    systemFolded = true
                }
                append(message.content)
                append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
    }
}
