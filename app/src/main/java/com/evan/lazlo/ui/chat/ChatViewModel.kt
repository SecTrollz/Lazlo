package com.evan.lazlo.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evan.lazlo.ai.AiCoreDownloadState
import com.evan.lazlo.ai.AiCoreProvider
import com.evan.lazlo.ai.AiProvider
import com.evan.lazlo.ai.AiProviderFactory
import com.evan.lazlo.ai.ChatMessage
import com.evan.lazlo.ai.LocalModelDownloadState
import com.evan.lazlo.ai.LocalModelDownloader
import com.evan.lazlo.core.SecretStore
import com.evan.lazlo.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the "which AI backend" picker. */
data class BackendRow(
    val id: String,
    val displayName: String,
    val explanation: String,
    val isOnDevice: Boolean,
    val isReady: Boolean,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val activeProviderId: String = AiBackendCopy.API_KEY_PROVIDER_ID,
    val backendRows: List<BackendRow> = emptyList(),
    /** Real "is a key actually saved" per BYOK provider id — distinct from [BackendRow.isReady], which for these rows just mirrors this map. */
    val apiKeyConfiguredByProvider: Map<String, Boolean> = emptyMap(),
    val backendLoading: Boolean = true,
    val aiCoreSetupRunning: Boolean = false,
    val aiCoreSetupState: AiCoreDownloadState? = null,
    /** Whether a Hugging Face token is saved — gates whether the local-model row offers "Download" or "Add token" first. */
    val huggingFaceTokenConfigured: Boolean = false,
    val localModelDownloadRunning: Boolean = false,
    val localModelDownloadState: LocalModelDownloadState? = null,
) {
    /** The row for whichever backend is currently selected, if it's known yet. */
    val activeBackend: BackendRow? get() = backendRows.find { it.id == activeProviderId }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val secretStore = SecretStore(application)
    private val aiProviderFactory = AiProviderFactory(application, settings, secretStore)
    private val localModelDownloader = LocalModelDownloader(application, secretStore)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** The provider actually in use for the current/next message; rebuilt whenever the backend choice changes. */
    private var activeProvider: AiProvider? = null

    init {
        viewModelScope.launch {
            // refreshBackendRows() first, not after: it already probes
            // AiCoreProvider.isReady() for every candidate's checkmark
            // (see its `ready` map below), so resolveInitialBackend() can
            // read that result straight off the rows it just populated
            // instead of instantiating a second AiCoreProvider and paying
            // for — or, worse, redundantly *triggering* — that same probe
            // (isReady() is also what kicks off Gemini Nano's first-run
            // download) a second time in the same launch.
            refreshBackendRows()
            resolveInitialBackend()
        }
    }

    /**
     * Picks what plays on the very first launch, before the user has ever
     * touched the backend picker — [Settings.aiChoice] on its own would
     * silently default to the Anthropic BYOK row, which does nothing
     * until a key's typed in. Instead: AICore first (fully offline, zero
     * setup, if this device/account has the on-device LLM feature
     * switched on — [refreshBackendRows] already checked), then a model
     * file [LocalModelDownloader] already fetched in an earlier session,
     * and only fall through to the BYOK default if neither offline option
     * is usable yet. Whichever one works gets persisted via
     * [Settings.setAiChoice] so this only ever runs once, not on every
     * app open.
     */
    private suspend fun resolveInitialBackend() {
        if (settings.hasChosenAiBackend()) {
            _uiState.update { it.copy(activeProviderId = choiceToProviderId(settings.aiChoice())) }
            return
        }
        val aiCoreReady = _uiState.value.backendRows.find { it.id == AiBackendCopy.AICORE_PROVIDER_ID }?.isReady == true
        val autoChoice = when {
            aiCoreReady -> Settings.AiChoice.AiCore
            else -> localModelDownloader.downloadedModelPath()?.let { Settings.AiChoice.LocalModel(it) }
        }
        if (autoChoice != null) {
            settings.setAiChoice(autoChoice)
            _uiState.update { it.copy(activeProviderId = choiceToProviderId(autoChoice)) }
        } else {
            _uiState.update { it.copy(activeProviderId = choiceToProviderId(settings.aiChoice())) }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun selectBackend(providerId: String) {
        viewModelScope.launch {
            activeProvider?.close()
            activeProvider = null
            val choice = when (providerId) {
                AiBackendCopy.AICORE_PROVIDER_ID -> Settings.AiChoice.AiCore
                AiBackendCopy.MEDIAPIPE_PROVIDER_ID -> {
                    // Prefer whatever path is already configured (a manually
                    // supplied file, or a prior download); fall back to a
                    // download this session already completed but hadn't
                    // been switched to yet, e.g. after downloading while a
                    // different backend was still selected.
                    val existingPath = (settings.aiChoice() as? Settings.AiChoice.LocalModel)?.path?.takeIf { it.isNotBlank() }
                    Settings.AiChoice.LocalModel(existingPath ?: localModelDownloader.downloadedModelPath() ?: "")
                }
                AiBackendCopy.OPENROUTER_PROVIDER_ID -> Settings.AiChoice.ApiKey(AiBackendCopy.OPENROUTER_PROVIDER_ID)
                else -> Settings.AiChoice.ApiKey(AiBackendCopy.API_KEY_PROVIDER_ID)
            }
            settings.setAiChoice(choice)
            _uiState.update { it.copy(activeProviderId = providerId, errorMessage = null) }
            refreshBackendRows()
        }
    }

    /** Saves a BYOK key (never surfaced back in plaintext) for whichever provider [providerId] names, and refreshes readiness. */
    fun saveApiKey(providerId: String, rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return
        secretStore.setApiKey(providerId, trimmed)
        viewModelScope.launch { refreshBackendRows() }
    }

    fun clearApiKey(providerId: String) {
        secretStore.clearApiKey(providerId)
        viewModelScope.launch { refreshBackendRows() }
    }

    /** Saves the Hugging Face token [LocalModelDownloader] needs to fetch the offline model — same never-shown-again handling as a BYOK key. */
    fun saveHuggingFaceToken(rawToken: String) {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) return
        localModelDownloader.saveToken(trimmed)
        viewModelScope.launch { refreshBackendRows() }
    }

    fun clearHuggingFaceToken() {
        localModelDownloader.clearToken()
        viewModelScope.launch { refreshBackendRows() }
    }

    /**
     * Downloads the offline model and, on success, switches straight to
     * it — same "finish the setup, then just use it" shape as
     * [setupAiCore]. Requires a token to already be saved; the picker UI
     * only shows this action once one is, so reaching this without one
     * would be a UI bug, not a user-facing error worth its own message.
     */
    fun startLocalModelDownload() {
        if (_uiState.value.localModelDownloadRunning) return
        _uiState.update { it.copy(localModelDownloadRunning = true, localModelDownloadState = null) }
        viewModelScope.launch {
            val progressJob = launch {
                localModelDownloader.downloadState.collect { state ->
                    _uiState.update { it.copy(localModelDownloadState = state) }
                }
            }
            localModelDownloader.download()
            progressJob.cancel()
            // Read the terminal state directly rather than trust that the
            // collector above already delivered it into _uiState — download()
            // returning only guarantees downloadState.value itself is final,
            // not that a StateFlow collector running in a separate coroutine
            // has processed that last emission before progressJob.cancel() runs.
            val finalState = localModelDownloader.downloadState.value
            _uiState.update { it.copy(localModelDownloadRunning = false, localModelDownloadState = finalState) }
            if (finalState is LocalModelDownloadState.Completed) {
                selectBackend(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)
            } else {
                refreshBackendRows()
            }
        }
    }

    fun dismissLocalModelDownload() {
        localModelDownloader.resetState()
        _uiState.update { it.copy(localModelDownloadState = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Explicit "set up Gemini Nano now" action for the backend picker:
     * runs [AiCoreProvider.provisionAndPrepare] against a scratch
     * provider instance (not [activeProvider] — this can run before the
     * user has even picked AICore as their active backend) while
     * streaming its [AiCoreProvider.downloadState] into the UI, so a
     * first-time user gets real progress instead of the setup only ever
     * happening silently the first time they hit send.
     */
    fun setupAiCore() {
        if (_uiState.value.aiCoreSetupRunning) return
        _uiState.update { it.copy(aiCoreSetupRunning = true, aiCoreSetupState = null) }
        viewModelScope.launch {
            val provider = AiCoreProvider(getApplication())
            val progressJob = launch {
                provider.downloadState.collect { state ->
                    _uiState.update { it.copy(aiCoreSetupState = state) }
                }
            }
            val ready = withContext(Dispatchers.IO) { provider.provisionAndPrepare() }
            progressJob.cancel()
            provider.close()
            _uiState.update { current ->
                current.copy(
                    aiCoreSetupRunning = false,
                    // A successful prepare means there's nothing left to show;
                    // a failed one keeps whatever AiCoreDownloadState.Failed
                    // the provider already published so the reason stays visible.
                    aiCoreSetupState = if (ready) null else current.aiCoreSetupState,
                )
            }
            refreshBackendRows()
        }
    }

    fun dismissAiCoreSetup() {
        _uiState.update { it.copy(aiCoreSetupState = null) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() || state.isSending) return

        _uiState.update {
            it.copy(
                messages = ChatTranscript.startAssistantReply(ChatTranscript.appendUser(it.messages, text)),
                inputText = "",
                isSending = true,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            val provider = try {
                activeProviderOrThrow()
            } catch (t: Throwable) {
                failCurrentReply(t)
                return@launch
            }

            val historySnapshot = _uiState.value.messages.dropLast(1) // exclude the empty assistant placeholder
            provider.streamChat(historySnapshot)
                .catch { t -> failCurrentReply(t) }
                .collect { token ->
                    _uiState.update { it.copy(messages = ChatTranscript.applyToken(it.messages, token)) }
                    if (token.isFinal) _uiState.update { it.copy(isSending = false) }
                }
            _uiState.update { it.copy(isSending = false) }
        }
    }

    private fun failCurrentReply(t: Throwable) {
        _uiState.update {
            it.copy(
                messages = ChatTranscript.dropTrailingEmptyAssistant(it.messages),
                isSending = false,
                errorMessage = t.message ?: "Something went wrong talking to this backend.",
            )
        }
    }

    /** Gets (building if needed) the provider for the currently selected backend, off the main thread. */
    private suspend fun activeProviderOrThrow(): AiProvider {
        activeProvider?.let { return it }
        // Settings.aiChoiceBlocking() (which provider() calls internally)
        // reads DataStore via runBlocking; running it on Dispatchers.IO
        // instead of the caller's Main-derived viewModelScope dispatcher
        // keeps that blocking read off the UI thread.
        val built = withContext(Dispatchers.IO) { aiProviderFactory.provider() }
        activeProvider = built
        return built
    }

    private suspend fun refreshBackendRows() {
        _uiState.update { it.copy(backendLoading = true) }
        val localPath = (settings.aiChoice() as? Settings.AiChoice.LocalModel)?.path?.takeIf { it.isNotBlank() }
        val ready = withContext(Dispatchers.IO) { aiProviderFactory.availableProviders(localPath) }
            .associate { it.id to true }
        // availableProviders() always includes every BYOK backend (a key
        // can be entered later), so presence in `ready` doesn't mean a
        // key is actually configured — check the real source instead.
        val apiKeyConfiguredByProvider = withContext(Dispatchers.IO) {
            mapOf(
                AiBackendCopy.API_KEY_PROVIDER_ID to (secretStore.getApiKey(AiBackendCopy.API_KEY_PROVIDER_ID) != null),
                AiBackendCopy.OPENROUTER_PROVIDER_ID to (secretStore.getApiKey(AiBackendCopy.OPENROUTER_PROVIDER_ID) != null),
            )
        }
        val huggingFaceTokenConfigured = withContext(Dispatchers.IO) { localModelDownloader.isTokenConfigured() }
        val downloadedModelPath = withContext(Dispatchers.IO) { localModelDownloader.downloadedModelPath() }

        val rows = listOf(
            BackendRow(
                id = AiBackendCopy.API_KEY_PROVIDER_ID,
                displayName = "Your own API key (Anthropic)",
                explanation = AiBackendCopy.explanation(AiBackendCopy.API_KEY_PROVIDER_ID),
                isOnDevice = false,
                isReady = apiKeyConfiguredByProvider.getValue(AiBackendCopy.API_KEY_PROVIDER_ID),
            ),
            BackendRow(
                id = AiBackendCopy.OPENROUTER_PROVIDER_ID,
                displayName = "Your own API key (OpenRouter)",
                explanation = AiBackendCopy.explanation(AiBackendCopy.OPENROUTER_PROVIDER_ID),
                isOnDevice = false,
                isReady = apiKeyConfiguredByProvider.getValue(AiBackendCopy.OPENROUTER_PROVIDER_ID),
            ),
            BackendRow(
                id = AiBackendCopy.AICORE_PROVIDER_ID,
                displayName = "Gemini Nano (on-device)",
                explanation = AiBackendCopy.explanation(AiBackendCopy.AICORE_PROVIDER_ID),
                isOnDevice = true,
                isReady = ready.containsKey(AiBackendCopy.AICORE_PROVIDER_ID),
            ),
            BackendRow(
                id = AiBackendCopy.MEDIAPIPE_PROVIDER_ID,
                displayName = "Local model file (on-device)",
                explanation = when {
                    localPath != null -> AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)
                    downloadedModelPath != null -> "${LocalModelDownloader.MODEL_DISPLAY_NAME} is downloaded and ready — select this row to switch to it."
                    else -> "No model file configured yet. ${AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)}"
                },
                isOnDevice = true,
                isReady = ready.containsKey(AiBackendCopy.MEDIAPIPE_PROVIDER_ID) || downloadedModelPath != null,
            ),
        )
        _uiState.update {
            it.copy(
                backendRows = rows,
                apiKeyConfiguredByProvider = apiKeyConfiguredByProvider,
                huggingFaceTokenConfigured = huggingFaceTokenConfigured,
                backendLoading = false,
            )
        }
    }

    private fun choiceToProviderId(choice: Settings.AiChoice): String = when (choice) {
        is Settings.AiChoice.AiCore -> AiBackendCopy.AICORE_PROVIDER_ID
        is Settings.AiChoice.LocalModel -> AiBackendCopy.MEDIAPIPE_PROVIDER_ID
        is Settings.AiChoice.ApiKey -> choice.providerId
    }

    override fun onCleared() {
        activeProvider?.close()
        activeProvider = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(application) as T
    }
}
