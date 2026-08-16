package com.evan.lazlo.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evan.lazlo.ai.AiCoreDownloadState
import com.evan.lazlo.ai.ChatMessage
import com.evan.lazlo.ai.LocalModelDownloadState
import com.evan.lazlo.ai.LocalModelDownloader
import com.evan.lazlo.ui.theme.FootstepLoader
import com.evan.lazlo.ui.theme.ScrollCard

/**
 * The chat tab: the message transcript, the input row, and — up top —
 * which AI backend is currently answering, with a one-tap way to switch
 * or to add/replace an API key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    var showBackendPicker by remember { mutableStateOf(false) }
    // Which BYOK provider's key dialog is open, if any — null means closed.
    // Nullable rather than a Boolean because there are now two BYOK
    // backends (Anthropic, OpenRouter), each with its own stored key.
    var manageKeyProviderId by remember { mutableStateOf<String?>(null) }
    var showHuggingFaceTokenDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackendSwitcherBar(
            state = state,
            onClick = { showBackendPicker = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                ChatEmptyState(backendReady = state.activeBackend?.isReady == true)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { message -> MessageBubble(message) }
                    if (state.isSending) {
                        item { TypingIndicator() }
                    }
                }
            }
        }

        state.errorMessage?.let { error ->
            ErrorBanner(message = error, onDismiss = viewModel::dismissError)
        }

        ChatInputRow(
            text = state.inputText,
            enabled = !state.isSending,
            onTextChange = viewModel::onInputTextChange,
            onSend = viewModel::sendMessage,
        )
    }

    if (showBackendPicker) {
        BackendPickerDialog(
            state = state,
            onSelect = { id ->
                viewModel.selectBackend(id)
                showBackendPicker = false
            },
            onManageKey = { providerId ->
                showBackendPicker = false
                manageKeyProviderId = providerId
            },
            onSetupAiCore = viewModel::setupAiCore,
            onManageHuggingFaceToken = {
                showBackendPicker = false
                showHuggingFaceTokenDialog = true
            },
            onStartLocalModelDownload = viewModel::startLocalModelDownload,
            onDismissLocalModelDownload = viewModel::dismissLocalModelDownload,
            onDismiss = { showBackendPicker = false },
        )
    }

    manageKeyProviderId?.let { providerId ->
        ApiKeyDialog(
            providerId = providerId,
            providerDisplayName = AiBackendCopy.serviceName(providerId),
            alreadyConfigured = state.apiKeyConfiguredByProvider[providerId] == true,
            onSave = { key ->
                viewModel.saveApiKey(providerId, key)
                manageKeyProviderId = null
            },
            onClear = {
                viewModel.clearApiKey(providerId)
                manageKeyProviderId = null
            },
            onDismiss = { manageKeyProviderId = null },
        )
    }

    if (showHuggingFaceTokenDialog) {
        HuggingFaceTokenDialog(
            alreadyConfigured = state.huggingFaceTokenConfigured,
            onSave = { token ->
                viewModel.saveHuggingFaceToken(token)
                showHuggingFaceTokenDialog = false
            },
            onClear = {
                viewModel.clearHuggingFaceToken()
                showHuggingFaceTokenDialog = false
            },
            onDismiss = { showHuggingFaceTokenDialog = false },
        )
    }
}

@Composable
private fun BackendSwitcherBar(state: ChatUiState, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("AI backend", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = state.activeBackend?.displayName ?: "Choose a backend",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onClick) { Text("Change") }
        }
    }
}

@Composable
private fun ChatEmptyState(backendReady: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text("Say hello", style = MaterialTheme.typography.titleMedium)
        Text(
            if (backendReady) {
                "Type a message below to start chatting."
            } else {
                "Pick or set up an AI backend above, then type a message below."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = message.content.ifEmpty { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FootstepLoader(footprintSize = 9.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ChatInputRow(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() }),
            maxLines = 5,
        )
        Spacer(modifier = Modifier.size(4.dp))
        IconButton(onClick = onSend, enabled = enabled && text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
        }
    }
}

@Composable
private fun BackendPickerDialog(
    state: ChatUiState,
    onSelect: (String) -> Unit,
    onManageKey: (providerId: String) -> Unit,
    onSetupAiCore: () -> Unit,
    onManageHuggingFaceToken: () -> Unit,
    onStartLocalModelDownload: () -> Unit,
    onDismissLocalModelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Choose an AI backend", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This decides where your messages go.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                state.backendRows.forEach { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            // Deliberately not gated on row.isReady: that
                            // flag reflects a point-in-time check (e.g. an
                            // on-device model still provisioning on first
                            // use), and nothing re-runs it while this
                            // dialog is open. Gating selection on it would
                            // mean a backend that becomes ready a few
                            // seconds later stays permanently disabled
                            // until some unrelated action happens to
                            // refresh it. Picking a not-yet-ready backend
                            // is safe either way — sending a message
                            // triggers its own readiness/preparation and
                            // surfaces a clear error if it genuinely can't
                            // proceed (e.g. no API key saved yet).
                            RadioButton(
                                selected = row.id == state.activeProviderId,
                                onClick = { onSelect(row.id) },
                            )
                            Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.displayName, style = MaterialTheme.typography.bodyLarge)
                                    if (row.isReady) {
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "Ready to use",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(row.explanation, style = MaterialTheme.typography.bodySmall)
                                if (row.id == AiBackendCopy.API_KEY_PROVIDER_ID || row.id == AiBackendCopy.OPENROUTER_PROVIDER_ID) {
                                    val configured = state.apiKeyConfiguredByProvider[row.id] == true
                                    TextButton(onClick = { onManageKey(row.id) }, modifier = Modifier.padding(top = 2.dp)) {
                                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(if (configured) "Change API key" else "Add API key")
                                    }
                                }
                                if (row.id == AiBackendCopy.AICORE_PROVIDER_ID) {
                                    AiCoreSetupSection(
                                        isReady = row.isReady,
                                        running = state.aiCoreSetupRunning,
                                        setupState = state.aiCoreSetupState,
                                        onSetup = onSetupAiCore,
                                    )
                                }
                                if (row.id == AiBackendCopy.MEDIAPIPE_PROVIDER_ID) {
                                    LocalModelDownloadSection(
                                        alreadyAvailable = row.isReady,
                                        tokenConfigured = state.huggingFaceTokenConfigured,
                                        running = state.localModelDownloadRunning,
                                        downloadState = state.localModelDownloadState,
                                        onAddToken = onManageHuggingFaceToken,
                                        onDownload = onStartLocalModelDownload,
                                        onDismissFailure = onDismissLocalModelDownload,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline "get Gemini Nano ready" control shown under the AICore row in
 * [BackendPickerDialog]. First-time users don't need to know that
 * "ready" means a native model has to be downloaded and prepared on
 * device — they get a button, a real progress bar while it happens, and,
 * if it fails, the plain-language reason from [com.evan.lazlo.ai.AiCoreDiagnosis]
 * instead of a raw AICore error code.
 */
@Composable
private fun AiCoreSetupSection(
    isReady: Boolean,
    running: Boolean,
    setupState: AiCoreDownloadState?,
    onSetup: () -> Unit,
) {
    when {
        running -> {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                val downloading = setupState as? AiCoreDownloadState.Downloading
                if (downloading != null && downloading.totalBytes > 0) {
                    val fraction = (downloading.bytesDownloaded.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Downloading Gemini Nano… ${downloading.bytesDownloaded / 1_000_000} / ${downloading.totalBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FootstepLoader(footprintSize = 9.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Setting up Gemini Nano on this device…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        setupState is AiCoreDownloadState.Failed -> {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    setupState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onSetup, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Try again")
                }
            }
        }
        !isReady -> {
            TextButton(onClick = onSetup, modifier = Modifier.padding(top = 2.dp)) {
                Text("Set up Gemini Nano")
            }
        }
    }
}

/**
 * Inline "get the free offline model" control shown under the local-model
 * row in [BackendPickerDialog] — the no-API-key path for devices where
 * AICore isn't available. Same shape as [AiCoreSetupSection] (a button, a
 * real progress bar, a plain-language failure reason), plus one extra
 * state that section doesn't need: no Hugging Face token saved yet, since
 * the model download itself is gated behind one — see
 * [LocalModelDownloader]'s doc comment for why that's unavoidable.
 */
@Composable
private fun LocalModelDownloadSection(
    alreadyAvailable: Boolean,
    tokenConfigured: Boolean,
    running: Boolean,
    downloadState: LocalModelDownloadState?,
    onAddToken: () -> Unit,
    onDownload: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    when {
        running -> {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                val downloading = downloadState as? LocalModelDownloadState.Downloading
                if (downloading != null && downloading.totalBytes > 0) {
                    val fraction = (downloading.bytesDownloaded.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Downloading ${LocalModelDownloader.MODEL_DISPLAY_NAME}… " +
                            "${downloading.bytesDownloaded / 1_000_000} / ${downloading.totalBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FootstepLoader(footprintSize = 9.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Starting download…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        downloadState is LocalModelDownloadState.Failed -> {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    downloadState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    TextButton(onClick = onDownload) { Text("Try again") }
                    TextButton(onClick = onDismissFailure) { Text("Dismiss") }
                }
            }
        }
        // Already has a usable model (a prior download, or a manually
        // supplied path) — the row's own explanation text already covers
        // this; nothing extra to show here.
        alreadyAvailable -> {}
        !tokenConfigured -> {
            TextButton(onClick = onAddToken, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(4.dp))
                Text("Add Hugging Face token to download a free model")
            }
        }
        else -> {
            TextButton(onClick = onDownload, modifier = Modifier.padding(top = 2.dp)) {
                Text("Download ${LocalModelDownloader.MODEL_DISPLAY_NAME} (~${LocalModelDownloader.EXPECTED_SIZE_BYTES / 1_000_000}MB)")
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(
    providerId: String,
    providerDisplayName: String,
    alreadyConfigured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    // Advisory only — pasting the other BYOK provider's key here is the
    // single most common way a saved key ends up dead on arrival, and
    // without this it only ever surfaces later as an opaque "HTTP 401
    // authentication_error" from the wrong provider. Still lets Save stay
    // enabled: see AiBackendCopy.keyFormatWarning for why this doesn't block.
    val keyFormatWarning = remember(providerId, input) { AiBackendCopy.keyFormatWarning(providerId, input) }

    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    if (alreadyConfigured) "Change your $providerDisplayName API key" else "Add your $providerDisplayName API key",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (alreadyConfigured) {
                        "A key is already saved for this backend (it's never shown again once saved — enter a new one to replace it)."
                    } else {
                        "Stored only on this device, encrypted by Android Keystore. Never shown in plain text once saved."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    isError = keyFormatWarning != null,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (reveal) "Hide key while typing" else "Show key while typing",
                            )
                        }
                    },
                )
                keyFormatWarning?.let { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (alreadyConfigured) {
                    TextButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Remove saved key")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(input) }, enabled = input.isNotBlank()) { Text("Save") }
                }
            }
        }
    }
}

/**
 * Collects the free Hugging Face token [LocalModelDownloader] needs to
 * fetch the offline model. Same shape as [ApiKeyDialog] — a masked field,
 * encrypted storage, never shown again once saved — but with its own copy
 * spelling out the one-time setup: create an account, accept Gemma's
 * license, generate a token. That's friction [ApiKeyDialog] doesn't have,
 * but it's free and one-time, not a paid per-message key.
 */
@Composable
private fun HuggingFaceTokenDialog(
    alreadyConfigured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val formatWarning = remember(input) { AiBackendCopy.huggingFaceTokenFormatWarning(input) }

    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    if (alreadyConfigured) "Change your Hugging Face token" else "Add a Hugging Face token",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Free — only used to download ${LocalModelDownloader.MODEL_DISPLAY_NAME} once, nothing else. " +
                        "1) Create a free account and accept the license at ${LocalModelDownloader.MODEL_INFO_URL}. " +
                        "2) Generate a token at ${LocalModelDownloader.TOKEN_SETTINGS_URL}. 3) Paste it below.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Text(
                    if (alreadyConfigured) {
                        "A token is already saved (it's never shown again once saved — enter a new one to replace it)."
                    } else {
                        "Stored only on this device, encrypted by Android Keystore."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("hf_...") },
                    singleLine = true,
                    isError = formatWarning != null,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (reveal) "Hide token while typing" else "Show token while typing",
                            )
                        }
                    },
                )
                formatWarning?.let { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (alreadyConfigured) {
                    TextButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Remove saved token")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(input) }, enabled = input.isNotBlank()) { Text("Save") }
                }
            }
        }
    }
}
