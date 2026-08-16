package com.evan.lazlo.ui.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.evan.lazlo.browser.BrowserScript
import com.evan.lazlo.browser.ChromiumEngine
import com.evan.lazlo.browser.EngineKind
import com.evan.lazlo.browser.GeckoModuleDiagnosis
import com.evan.lazlo.browser.GeckoModuleState
import com.evan.lazlo.core.BrowserRecord
import com.evan.lazlo.core.DownloadRecord
import com.evan.lazlo.ui.theme.FootstepLoader
import com.evan.lazlo.ui.theme.ScrollCard

/**
 * The browsing tab: a real multi-tab browser — tab strip, address bar
 * (type a URL or a search), back/forward/reload, history, bookmarks, and
 * downloads — rendered by whichever engine is selected. Switching
 * engines or tabs tears down/re-navigates the single shared native view
 * rather than keeping multiple native engine instances alive at once
 * (see [BrowserTab]'s doc comment) — an unavoidable cost of actually
 * swapping rendering engines and of not paying GeckoView's per-session
 * memory cost N times over, not a bug — but the last URL for each tab is
 * remembered so switching back reopens the same page.
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
        TabStrip(
            tabs = state.tabs,
            activeTabId = state.activeTabId,
            onSelectTab = { tab ->
                viewModel.switchTab(tab.id)?.let { url -> engineRef?.loadUrl(url) }
            },
            onCloseTab = { tab ->
                viewModel.closeTab(tab.id)?.let { url -> engineRef?.loadUrl(url) }
            },
            onNewTab = { engineRef?.loadUrl(viewModel.newTab()) },
        )

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
            IconButton(onClick = { viewModel.toggleBookmark() }, enabled = state.currentUrl != null) {
                Icon(
                    if (state.isCurrentPageBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (state.isCurrentPageBookmarked) "Remove bookmark" else "Bookmark this page",
                    tint = if (state.isCurrentPageBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { viewModel.setEnginePickerVisible(true) },
                label = { Text("Engine: " + BrowserEngineCopy.shortLabel(state.engineKind)) },
                leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            AssistChip(
                onClick = { viewModel.setShowBookmarks(true) },
                label = { Text("Bookmarks (${state.bookmarks.size})") },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                modifier = Modifier.padding(end = 4.dp),
            )
            AssistChip(
                onClick = { viewModel.setShowHistory(true) },
                label = { Text("History") },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                modifier = Modifier.padding(end = 4.dp),
            )
            AssistChip(
                onClick = { viewModel.setShowDownloads(true) },
                label = { Text("Downloads (${state.downloads.size})") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            )
            AssistChip(
                onClick = { viewModel.setShowScripts(true) },
                label = { Text("Scripts (${state.scripts.size})") },
                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        state.lastDownloadStarted?.let { fileName ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Downloading $fileName — check your notification shade or Downloads folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissDownloadStartedMessage) { Text("Dismiss") }
                }
            }
        }

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
                        engine.onLoadingChanged = { loading ->
                            viewModel.onLoadingChanged(loading)
                            // Auto-run on page finish, not page start: a
                            // script that expects the page's own DOM/JS to
                            // already be in place (the normal case for a
                            // userscript) would find an empty document if
                            // it ran on onPageStarted instead.
                            if (!loading) {
                                engine.currentUrl()?.let { url ->
                                    viewModel.scriptsForUrl(url).forEach { engine.runScript(it.code) }
                                }
                            }
                        }
                        engine.onTitleChanged = { title -> viewModel.onTitleChanged(title) }
                        engine.onDownloadRequested = { request -> viewModel.onDownloadRequested(request) }
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

    if (state.showHistory) {
        RecordListDialog(
            title = "History",
            records = state.history,
            emptyMessage = "Nothing visited yet.",
            trailingAction = { TextButton(onClick = viewModel::clearHistory) { Text("Clear") } },
            onOpen = { url ->
                viewModel.openUrlInCurrentTab(url)
                engineRef?.loadUrl(url)
            },
            onDismiss = { viewModel.setShowHistory(false) },
        )
    }

    if (state.showBookmarks) {
        RecordListDialog(
            title = "Bookmarks",
            records = state.bookmarks,
            emptyMessage = "No bookmarks yet — tap the star next to the address bar to add one.",
            onOpen = { url ->
                viewModel.openUrlInCurrentTab(url)
                engineRef?.loadUrl(url)
            },
            onDismiss = { viewModel.setShowBookmarks(false) },
        )
    }

    if (state.showDownloads) {
        DownloadsDialog(
            downloads = state.downloads,
            onDismiss = { viewModel.setShowDownloads(false) },
        )
    }

    if (state.showScripts) {
        ScriptsDialog(
            scripts = state.scripts,
            onSetEnabled = viewModel::setScriptEnabled,
            onEdit = viewModel::startEditScript,
            onNew = viewModel::startNewScript,
            onDismiss = { viewModel.setShowScripts(false) },
        )
    }

    state.editingScript?.let { script ->
        ScriptEditorDialog(
            script = script,
            isNew = state.scripts.none { it.id == script.id },
            onSave = viewModel::saveScript,
            onDelete = { viewModel.deleteScript(script.id) },
            onDismiss = viewModel::dismissScriptEditor,
        )
    }
}

@Composable
private fun TabStrip(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (BrowserTab) -> Unit,
    onCloseTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            val isActive = tab.id == activeTabId
            Surface(
                onClick = { onSelectTab(tab) },
                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.title.ifBlank { tab.url.ifBlank { "New tab" } },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 120.dp),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (tabs.size > 1) {
                        IconButton(onClick = { onCloseTab(tab) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close this tab", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        item {
            IconButton(onClick = onNewTab) {
                Icon(Icons.Filled.Add, contentDescription = "Open a new tab")
            }
        }
    }
}

@Composable
private fun RecordListDialog(
    title: String,
    records: List<BrowserRecord>,
    emptyMessage: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailingAction?.invoke()
                }
                if (records.isEmpty()) {
                    Text(
                        emptyMessage,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(records, key = { it.url + it.timestampMillis }) { record ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = { onOpen(record.url) })
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(record.title.ifBlank { record.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    record.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun DownloadsDialog(downloads: List<DownloadRecord>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Downloads", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Handed off to Android's own Downloads app — files land in your device's normal Downloads folder.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                if (downloads.isEmpty()) {
                    Text("Nothing downloaded yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn {
                        items(downloads, key = { it.downloadManagerId }) { download ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(download.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    download.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
            }
        }
    }
}

/**
 * Lists saved [BrowserScript]s with an inline enable/disable switch (same
 * quick-toggle shape as [InspectorPillOverlay]'s Rules tab) plus a way
 * into the editor for a new or existing one.
 */
@Composable
private fun ScriptsDialog(
    scripts: List<BrowserScript>,
    onSetEnabled: (String, Boolean) -> Unit,
    onEdit: (BrowserScript) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scripts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onNew) { Text("New") }
                }
                Text(
                    "Runs in the page's own context once a matching page finishes loading — for automating " +
                        "repeat actions on sites you use, or testing sites you control. Scripts can't reach " +
                        "anything outside that page: no other tabs, no device APIs, no Lazlo data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (scripts.isEmpty()) {
                    Text(
                        "No scripts yet — tap New to add one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn {
                        items(scripts, key = { it.id }) { script ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = { onEdit(script) })
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        script.name.ifBlank { "Untitled script" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        script.urlContains.ifBlank { "No match pattern set" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Switch(checked = script.enabled, onCheckedChange = { onSetEnabled(script.id, it) })
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
            }
        }
    }
}

/** Add/edit form for one [BrowserScript] — name, URL match substring, and the JS body itself. */
@Composable
private fun ScriptEditorDialog(
    script: BrowserScript,
    isNew: Boolean,
    onSave: (BrowserScript) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(script.id) { mutableStateOf(script.name) }
    var urlContains by remember(script.id) { mutableStateOf(script.urlContains) }
    var code by remember(script.id) { mutableStateOf(script.code) }

    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    if (isNew) "New script" else "Edit script",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = urlContains,
                    onValueChange = { urlContains = it },
                    label = { Text("Runs on pages whose URL contains…") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("JavaScript") },
                    placeholder = { Text("document.querySelectorAll('...')…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 6,
                )
                Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete this script")
                        }
                    }
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = { onSave(script.copy(name = name, urlContains = urlContains, code = code)) },
                            enabled = urlContains.isNotBlank() && code.isNotBlank(),
                        ) { Text("Save") }
                    }
                }
            }
        }
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
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Downloading GeckoView", style = MaterialTheme.typography.titleMedium)
                Text(
                    "GeckoView isn't included by default to keep the app small. It only needs to download once.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                when (state) {
                    is GeckoModuleState.NotInstalled, GeckoModuleState.Installing -> {
                        // No byte count yet at this stage (that only exists
                        // once GeckoModuleState.Downloading reports real
                        // progress below) — an indefinite wait, so this
                        // uses the same footstep motif as the rest of the
                        // app's "no known duration or size" states rather
                        // than an indeterminate progress bar.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FootstepLoader(footprintSize = 9.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                if (state == GeckoModuleState.Installing) "Installing…" else "Starting download…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                            GeckoModuleDiagnosis.messageFor(state.errorCode),
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
        ScrollCard {
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
