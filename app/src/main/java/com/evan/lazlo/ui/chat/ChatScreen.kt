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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
    var showApiKeyDialog by remember { mutableStateOf(false) }
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
            onManageKey = {
                showBackendPicker = false
                showApiKeyDialog = true
            },
            onSetupAiCore = viewModel::setupAiCore,
            onDismiss = { showBackendPicker = false },
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            alreadyConfigured = state.apiKeyConfigured,
            onSave = { key ->
                viewModel.saveApiKey(key)
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.clearApiKey()
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false },
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
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
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
    onManageKey: () -> Unit,
    onSetupAiCore: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                                if (row.id == AiBackendCopy.API_KEY_PROVIDER_ID) {
                                    TextButton(onClick = onManageKey, modifier = Modifier.padding(top = 2.dp)) {
                                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(if (state.apiKeyConfigured) "Change API key" else "Add API key")
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
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
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

@Composable
private fun ApiKeyDialog(
    alreadyConfigured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alreadyConfigured) "Change API key" else "Add your API key") },
        text = {
            Column {
                Text(
                    if (alreadyConfigured) {
                        "A key is already saved for this backend (it's never shown again once saved — enter a new one to replace it)."
                    } else {
                        "Stored only on this device, encrypted by Android Keystore. Never shown in plain text once saved."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
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
                if (alreadyConfigured) {
                    TextButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Remove saved key")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(input) }, enabled = input.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
