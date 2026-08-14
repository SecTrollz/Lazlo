package com.evan.lazlo.ui.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evan.lazlo.browser.BrowserEngine
import com.evan.lazlo.browser.ChromiumEngine
import com.evan.lazlo.browser.EngineKind
import com.evan.lazlo.browser.GeckoModuleState

/**
 * The browsing tab: an address bar (type a URL or a search), back /
 * forward / reload, and the page itself rendered by whichever engine is
 * selected. Switching engines (via the chip below the address bar, or
 * the dialog it opens) tears down and rebuilds the native view — an
 * unavoidable cost of actually swapping rendering engines, not a bug —
 * but the last URL is remembered so the new engine reopens the same page.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel(
        factory = BrowserViewModel.Factory(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    var engineRef by remember { mutableStateOf<BrowserEngine?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { engineRef?.goBack() }, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back to the previous page")
            }
            IconButton(onClick = { engineRef?.goForward() }, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go forward")
            }
            IconButton(onClick = { engineRef?.currentUrl()?.let { engineRef?.loadUrl(it) } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload this page")
            }
            OutlinedTextField(
                value = state.addressBarText,
                onValueChange = viewModel::onAddressBarTextChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search or type a website address") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    engineRef?.loadUrl(viewModel.resolveSubmittedAddress())
                }),
            )
        }

        AssistChip(
            onClick = { viewModel.setEnginePickerVisible(true) },
            label = { Text("Browser engine: " + BrowserEngineCopy.shortLabel(state.engineKind)) },
            leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f)) {
            key(state.engineKind) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val container = FrameLayout(ctx)
                        val engine = when (state.engineKind) {
                            EngineKind.CHROMIUM -> ChromiumEngine(ctx)
                            // Only reached once the module install below
                            // has already completed — GeckoEngine isn't a
                            // compile-time dependency of this module, so
                            // it's loaded via reflection once Play
                            // confirms it's actually installed.
                            EngineKind.GECKO -> viewModel.browserEngineLoader.newGeckoEngine()
                        }
                        engine.onUrlChanged = { url ->
                            viewModel.onNavigationStateChanged(url, engine.canGoBack(), engine.canGoForward())
                        }
                        engine.onLoadingChanged = { loading -> viewModel.onLoadingChanged(loading) }
                        engine.attach(container)
                        engine.loadUrl(viewModel.startUrl())
                        engineRef = engine
                        container
                    },
                    onRelease = {
                        engineRef?.destroy()
                        engineRef = null
                    },
                )
            }
        }
    }

    if (state.showEnginePicker) {
        EnginePickerDialog(
            selected = state.engineKind,
            onSelect = viewModel::setEngine,
            onDismiss = { viewModel.setEnginePickerVisible(false) },
        )
    }

    state.geckoModuleState?.let { moduleState ->
        GeckoModuleInstallDialog(
            state = moduleState,
            onDismiss = viewModel::dismissGeckoModuleInstall,
            onConfirmInstall = { activity, sessionState ->
                viewModel.browserEngineLoader.confirmInstall(sessionState, activity, GECKO_INSTALL_CONFIRMATION_REQUEST_CODE)
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val GECKO_INSTALL_CONFIRMATION_REQUEST_CODE = 4201

/**
 * GeckoView isn't bundled in the base app (see BrowserEngineLoader) —
 * this shows while its ~30-50MB module downloads, so picking it from
 * the engine list doesn't just silently stall.
 */
@Composable
private fun GeckoModuleInstallDialog(
    state: GeckoModuleState,
    onDismiss: () -> Unit,
    onConfirmInstall: (Activity, com.google.android.play.core.splitinstall.SplitInstallSessionState) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Downloading GeckoView", style = MaterialTheme.typography.titleMedium)
                Text(
                    "GeckoView isn't included by default to keep the app small. It only needs to download once.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                when (state) {
                    is GeckoModuleState.NotInstalled, GeckoModuleState.Installing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            if (state == GeckoModuleState.Installing) "Installing…" else "Starting download…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is GeckoModuleState.Downloading -> {
                        val fraction = if (state.totalBytes > 0) (state.bytesDownloaded.toFloat() / state.totalBytes) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${state.bytesDownloaded / 1_000_000}MB of ${state.totalBytes / 1_000_000}MB",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is GeckoModuleState.Installed -> {
                        Text("Done — switching to GeckoView.", style = MaterialTheme.typography.bodySmall)
                    }
                    is GeckoModuleState.RequiresConfirmation -> {
                        Text(
                            "This download needs your confirmation (usually because it's large or you're on cellular data).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        val context = LocalContext.current
                        Button(onClick = {
                            context.findActivity()?.let { onConfirmInstall(it, state.state) }
                        }) {
                            Text("Continue")
                        }
                    }
                    is GeckoModuleState.Failed -> {
                        Text(
                            "The download didn't complete (error ${state.errorCode}). Staying on the current engine.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnginePickerDialog(
    selected: EngineKind,
    onSelect: (EngineKind) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Choose a browser engine", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This changes how web pages are rendered. Switching reloads the current tab.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                EngineKind.entries.forEach { kind ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = kind == selected, onClick = { onSelect(kind) })
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(BrowserEngineCopy.shortLabel(kind), style = MaterialTheme.typography.bodyLarge)
                            Text(BrowserEngineCopy.explanation(kind), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
