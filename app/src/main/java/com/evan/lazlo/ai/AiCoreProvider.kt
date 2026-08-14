package com.evan.lazlo.ai

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** Progress of AICore's own on-device model download, surfaced for the chat UI's readiness state. */
sealed class AiCoreDownloadState {
    data object Idle : AiCoreDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : AiCoreDownloadState()
    data class Failed(val message: String) : AiCoreDownloadState()
}

/**
 * Wraps the on-device Gemini Nano model exposed by AICore on supported
 * Pixel devices (Tensor G3 and later — e.g. Pixel 8/9 series). Fully
 * offline once the model itself is provisioned on-device — nothing here
 * makes a network call directly; AICore's own on-device feature
 * delivery is what downloads the model weights the first time this
 * runs on a fresh install, entirely below this class.
 *
 * [isReady] doesn't just construct a [GenerativeModel] and declare
 * victory — it calls [GenerativeModel.prepareInferenceEngine], which is
 * what actually provisions the model (triggering that first-run
 * download if the weights aren't on the device yet) before anything
 * tries to generate against it. An earlier version of this class skipped
 * that call entirely, which would have looked fine in this sandbox
 * (nothing here can exercise a real AICore download) but risked
 * `generateContentStream` failing or hanging on a real device the first
 * time this backend was actually used.
 */
class AiCoreProvider(private val context: Context) : AiProvider {

    override val id = "aicore-nano"
    override val displayName = "Gemini Nano (on-device)"
    override val isOnDevice = true

    private var model: GenerativeModel? = null
    private var prepared = false

    private val _downloadState = MutableStateFlow<AiCoreDownloadState>(AiCoreDownloadState.Idle)
    val downloadState: StateFlow<AiCoreDownloadState> = _downloadState.asStateFlow()

    override suspend fun isReady(): Boolean = runCatching {
        val m = model ?: buildModel().also { model = it }
        if (!prepared) {
            m.prepareInferenceEngine()
            prepared = true
        }
        true
    }.getOrElse {
        // A failed prepare leaves this model unusable — drop it so the
        // next isReady() call starts clean instead of being stuck
        // thinking it's already "prepared."
        model = null
        prepared = false
        false
    }

    private fun buildModel(): GenerativeModel {
        val generationConfig = GenerationConfig.builder().apply { context = this@AiCoreProvider.context }.build()
        val downloadConfig = DownloadConfig(
            object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    _downloadState.value = AiCoreDownloadState.Downloading(0, bytesToDownload)
                }

                override fun onDownloadProgress(totalBytesDownloaded: Long) {
                    val totalBytes = (_downloadState.value as? AiCoreDownloadState.Downloading)?.totalBytes ?: totalBytesDownloaded
                    _downloadState.value = AiCoreDownloadState.Downloading(totalBytesDownloaded, totalBytes)
                }

                override fun onDownloadCompleted() {
                    _downloadState.value = AiCoreDownloadState.Idle
                }

                override fun onDownloadFailed(errorMessage: String, e: GenerativeAIException) {
                    _downloadState.value = AiCoreDownloadState.Failed(errorMessage)
                }
            }
        )
        return GenerativeModel(generationConfig, downloadConfig)
    }

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

    /** Frees the native model resources AICore allocated for [model]; safe to call even if it was never loaded. */
    override fun close() {
        model?.close()
        model = null
        prepared = false
    }
}
