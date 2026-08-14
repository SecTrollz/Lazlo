package com.evan.lazlo.ui.browser

import android.app.Application
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
import com.evan.lazlo.browser.GeckoEngine

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
                            EngineKind.GECKO -> GeckoEngine(ctx)
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
