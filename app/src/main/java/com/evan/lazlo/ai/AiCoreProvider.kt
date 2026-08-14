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
    data class Failed(val message: String, val isDeviceIneligible: Boolean = false) : AiCoreDownloadState()
}

/**
 * Turns AICore's raw [GenerativeAIException] (a numeric error code and a
 * terse internal message — e.g. "AICore failed with error type
 * 2-INFERENCE_ERROR and error code 8-NOT_AVAILABLE: Required LLM feature
 * not found") into something an end user can actually act on. Pure and
 * unit-testable on its own: takes the error code and message as plain
 * values rather than the exception itself, since [GenerativeAIException]'s
 * own subclass constructors are `internal` to the SDK's module and can't
 * be instantiated from this app's test source set — the
 * [GenerativeAIException.ErrorCode] constants it switches on are public,
 * and that's all this needs.
 */
internal object AiCoreDiagnosis {

    /** True for codes that mean "this device/account can't use AICore right now," not a transient hiccup. */
    fun isDeviceIneligible(errorCode: Int): Boolean = errorCode == GenerativeAIException.ErrorCode.NOT_AVAILABLE ||
        errorCode == GenerativeAIException.ErrorCode.NEEDS_SYSTEM_UPDATE

    fun messageFor(e: GenerativeAIException): String = messageFor(e.errorCode, e.message)

    fun messageFor(errorCode: Int, rawMessage: String?): String = when (errorCode) {
        GenerativeAIException.ErrorCode.NOT_AVAILABLE ->
            "Gemini Nano isn't available on this device yet (AICore error 8, NOT_AVAILABLE: " +
                "\"Required LLM feature not found\"). This shows up even on Pixels that officially " +
                "support AICore — it means Play Services hasn't switched the on-device LLM feature on " +
                "for this device/account yet, not that this app is missing something. Two things worth " +
                "trying: 1) Settings → Apps → see all apps → \"Android AICore\" → make sure it's updated " +
                "via the Play Store (search \"AICore\" if it's not listed as an app yet); 2) some devices " +
                "need to be enrolled in Google's AICore Developer Preview program before this feature " +
                "unlocks at all. This can't be fixed from inside Lazlo — it's controlled by Google Play " +
                "services on this device."
        GenerativeAIException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
            "This device needs a system update before Gemini Nano can run on it. Check Settings → " +
                "System → System update, then try again."
        GenerativeAIException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
            "Not enough free storage to download the on-device model. Free up some space and try again."
        GenerativeAIException.ErrorCode.BINDING_FAILURE,
        GenerativeAIException.ErrorCode.SERVICE_DISCONNECTED,
        GenerativeAIException.ErrorCode.BINDING_DIED,
        GenerativeAIException.ErrorCode.NULL_BINDING ->
            "Couldn't connect to the on-device AI service on this device. Try again — if it keeps " +
                "happening, restart the device."
        GenerativeAIException.ErrorCode.BUSY ->
            "The on-device model is busy handling another request. Try again in a moment."
        else ->
            "${rawMessage ?: "AICore couldn't prepare the on-device model"} (AICore error $errorCode)."
    }
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
            _downloadState.value = AiCoreDownloadState.Idle
        }
        true
    }.getOrElse { t ->
        // A failed prepare leaves this model unusable — drop it so the
        // next isReady() call starts clean instead of being stuck
        // thinking it's already "prepared."
        model = null
        prepared = false
        _downloadState.value = AiCoreDownloadState.Failed(
            message = (t as? GenerativeAIException)?.let(AiCoreDiagnosis::messageFor)
                ?: (t.message ?: "Couldn't prepare Gemini Nano on this device."),
            isDeviceIneligible = (t as? GenerativeAIException)?.let { AiCoreDiagnosis.isDeviceIneligible(it.errorCode) } ?: false,
        )
        false
    }

    /**
     * Explicit "set up Gemini Nano now" entry point: what a first-run
     * setup screen calls so a user starting from zero can trigger
     * provisioning deliberately — with [downloadState] driving a real
     * progress UI — instead of this only ever happening silently the
     * first time they hit send. Functionally identical to [isReady];
     * kept as its own clearly-named entry point because that's the
     * function a setup flow should call.
     */
    suspend fun provisionAndPrepare(): Boolean = isReady()

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
                    _downloadState.value = AiCoreDownloadState.Failed(
                        message = AiCoreDiagnosis.messageFor(e),
                        isDeviceIneligible = AiCoreDiagnosis.isDeviceIneligible(e.errorCode),
                    )
                }
            }
        )
        return GenerativeModel(generationConfig, downloadConfig)
    }

    override fun streamChat(history: List<ChatMessage>): Flow<ChatToken> = flow {
        val m = model ?: run { isReady(); model }
        ?: throw IllegalStateException(
            (_downloadState.value as? AiCoreDownloadState.Failed)?.message
                ?: "AICore unavailable on this device"
        )

        val prompt = history.joinToString("\n\n") { "${it.role}: ${it.content}" }
        // AICore's streaming callback API is adapted to a cold Flow here;
        // in the real client this wraps GenerativeModel.generateContentStream(prompt).
        try {
            m.generateContentStream(prompt).collect { chunk ->
                emit(ChatToken(chunk.text ?: ""))
            }
        } catch (e: GenerativeAIException) {
            // Surface the same plain-language diagnosis here as prepare
            // failures get — a raw "error code 8" is meaningless to a
            // first-time user reading the chat error banner.
            throw IllegalStateException(AiCoreDiagnosis.messageFor(e), e)
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
