package com.evan.lazlo.ai

import android.content.Context
import com.evan.lazlo.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Progress of the offline model download, surfaced for the backend picker's download UI. */
sealed class LocalModelDownloadState {
    data object Idle : LocalModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : LocalModelDownloadState()
    data class Failed(val message: String) : LocalModelDownloadState()
    data class Completed(val path: String) : LocalModelDownloadState()
}

/**
 * Fetches a specific, fixed, mobile-sized model — Gemma 3 270M, MediaPipe's
 * smallest instruction-tuned `.task` bundle — so [MediaPipeProvider] has
 * something to run without the user needing to source and side-load a
 * model file themselves. This is the "no API key" answer for devices
 * where [AiCoreProvider] (Gemini Nano) isn't available: still zero
 * per-message cost and fully offline once downloaded, just not zero-setup
 * the way AICore is — see the class-level note on why.
 *
 * **Why this needs a token at all**: every ready-to-use, pre-converted
 * `.task` bundle for MediaPipe's LLM Inference API — Gemma 3 270M
 * included — is published under Google's Gemma license on Hugging Face,
 * which gates the actual file bytes behind a logged-in account that's
 * clicked "accept" on [MODEL_INFO_URL] at least once (confirmed directly
 * against the Hugging Face API: the repo reports `"gated":"auto"`, and an
 * unauthenticated request for the file itself comes back
 * `401 GatedRepo`). There's no ungated equivalent to fall back to — other
 * small models (Phi-2, TinyLlama, Falcon-RW-1B, …) aren't published as
 * ready `.task` bundles at all, only as source weights that would need
 * MediaPipe's own multi-stage conversion pipeline to become one. A free
 * Hugging Face access token is the least friction available, not a
 * missing shortcut.
 */
class LocalModelDownloader(
    private val context: Context,
    private val secretStore: SecretStore,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val _downloadState = MutableStateFlow<LocalModelDownloadState>(LocalModelDownloadState.Idle)
    val downloadState: StateFlow<LocalModelDownloadState> = _downloadState.asStateFlow()

    fun isTokenConfigured(): Boolean = secretStore.getApiKey(HUGGINGFACE_TOKEN_ID) != null

    fun saveToken(token: String) {
        secretStore.setApiKey(HUGGINGFACE_TOKEN_ID, token.trim())
    }

    fun clearToken() {
        secretStore.clearApiKey(HUGGINGFACE_TOKEN_ID)
    }

    /** The path to hand [MediaPipeProvider] if a complete download already exists on this device, null otherwise. */
    fun downloadedModelPath(): String? =
        modelFile(context).takeIf { isCompleteDownload(it, EXPECTED_SIZE_BYTES) }?.path

    /**
     * Downloads [MODEL_FILENAME] to app-private storage, publishing
     * progress via [downloadState]. Writes to a `.part` file first and
     * only renames it into place on a full, successful read — a
     * half-written file must never look like a ready-to-use model to
     * [downloadedModelPath] (which is exactly what [isCompleteDownload]'s
     * exact-size check guards against, whether the partial write was left
     * behind by a crash, a cancellation, or this rename never running).
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        val token = secretStore.getApiKey(HUGGINGFACE_TOKEN_ID)
        if (token == null) {
            _downloadState.value = LocalModelDownloadState.Failed("No Hugging Face token saved yet.")
            return@withContext
        }

        val target = modelFile(context)
        val partial = File(context.filesDir, "$MODEL_FILENAME.part")
        _downloadState.value = LocalModelDownloadState.Downloading(0, EXPECTED_SIZE_BYTES)

        runCatching {
            val request = Request.Builder()
                .url(MODEL_DOWNLOAD_URL)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        when (response.code) {
                            401, 403 ->
                                "Hugging Face rejected this token (HTTP ${response.code}). This model is gated: " +
                                    "open $MODEL_INFO_URL in a browser while logged into the same account this " +
                                    "token belongs to, and click \"Agree and access repository\" — access is " +
                                    "checked live against your account on every request, so the same token " +
                                    "works immediately after that, no need to generate a new one. If this is a " +
                                    "fine-grained token rather than a plain \"Read\" token, also check it's " +
                                    "scoped to allow reading gated repos."
                            else -> "Download failed: HTTP ${response.code} ${response.message}".trim()
                        },
                    )
                }
                val body = response.body ?: throw IOException("Empty response body")
                val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_SIZE_BYTES
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            _downloadState.value = LocalModelDownloadState.Downloading(downloaded, total)
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Couldn't finalize the downloaded file.")
        }.onFailure { t ->
            partial.delete()
            _downloadState.value = LocalModelDownloadState.Failed(t.message ?: "Download failed.")
            return@withContext
        }

        _downloadState.value = LocalModelDownloadState.Completed(target.path)
    }

    fun resetState() {
        _downloadState.value = LocalModelDownloadState.Idle
    }

    companion object {
        const val HUGGINGFACE_TOKEN_ID = "huggingface"

        private const val MODEL_REPO = "litert-community/gemma-3-270m-it"

        /** The plain CPU-generic `.task` bundle — not the `-web` variants (browser/WASM) or the `.litertlm` NPU-specific ones, which this app's MediaPipe runtime doesn't target. */
        const val MODEL_FILENAME = "gemma3-270m-it-q8.task"
        const val MODEL_DISPLAY_NAME = "Gemma 3 270M"

        /** Verified directly against the Hugging Face API at the time this was written — re-check if downloads start failing on a size mismatch. */
        const val EXPECTED_SIZE_BYTES = 303_950_933L

        const val MODEL_DOWNLOAD_URL = "https://huggingface.co/$MODEL_REPO/resolve/main/$MODEL_FILENAME"
        const val MODEL_INFO_URL = "https://huggingface.co/$MODEL_REPO"
        const val TOKEN_SETTINGS_URL = "huggingface.co/settings/tokens"

        fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILENAME)

        /**
         * Pure so this is unit-testable without touching the network or
         * Android framework classes — [File] itself is plain JDK I/O.
         * A file only counts as a complete download if it exists and is
         * exactly [expectedSizeBytes]; anything else (missing, truncated,
         * a stray `.part` temp file) must read as "not downloaded yet"
         * rather than being handed to MediaPipe as a real model.
         */
        internal fun isCompleteDownload(file: File, expectedSizeBytes: Long): Boolean =
            file.exists() && file.length() == expectedSizeBytes
    }
}
