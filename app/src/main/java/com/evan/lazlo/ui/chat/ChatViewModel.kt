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
) {
    /** The row for whichever backend is currently selected, if it's known yet. */
    val activeBackend: BackendRow? get() = backendRows.find { it.id == activeProviderId }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val secretStore = SecretStore(application)
    private val aiProviderFactory = AiProviderFactory(application, settings, secretStore)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** The provider actually in use for the current/next message; rebuilt whenever the backend choice changes. */
    private var activeProvider: AiProvider? = null

    init {
        viewModelScope.launch {
            val choice = settings.aiChoice()
            _uiState.update { it.copy(activeProviderId = choiceToProviderId(choice)) }
            refreshBackendRows()
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
                AiBackendCopy.MEDIAPIPE_PROVIDER_ID ->
                    Settings.AiChoice.LocalModel((settings.aiChoice() as? Settings.AiChoice.LocalModel)?.path ?: "")
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
                explanation = if (localPath == null) {
                    "No model file configured yet. ${AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)}"
                } else {
                    AiBackendCopy.explanation(AiBackendCopy.MEDIAPIPE_PROVIDER_ID)
                },
                isOnDevice = true,
                isReady = ready.containsKey(AiBackendCopy.MEDIAPIPE_PROVIDER_ID),
            ),
        )
        _uiState.update {
            it.copy(
                backendRows = rows,
                apiKeyConfiguredByProvider = apiKeyConfiguredByProvider,
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
