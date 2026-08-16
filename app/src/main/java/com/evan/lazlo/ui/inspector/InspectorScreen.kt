package com.evan.lazlo.ui.inspector

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.evan.lazlo.proxy.TrafficEntry
import com.evan.lazlo.proxy.net.RewriteRule
import com.evan.lazlo.ui.theme.ScrollCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The traffic inspector tab: the on/off switch, the "why does this need
 * a certificate" explanation with the install action, and a live list
 * of what's been captured. Nothing here is hidden behind a menu — this
 * is the most trust-sensitive screen in the app, so every control that
 * touches the device's traffic or its certificate store explains itself
 * right where it lives.
 */
@Composable
fun InspectorScreen(
    viewModel: InspectorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = InspectorViewModel.Factory(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startServiceNow() else viewModel.onVpnConsentDenied()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Best-effort: the service still runs without it, it just won't show the "active" notification. */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ScrollCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Traffic inspector", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.isPreparing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Switch(
                        checked = state.inspectorEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                scope.launch {
                                    val consent = viewModel.turnOn()
                                    if (consent != null) vpnConsentLauncher.launch(consent) else viewModel.startServiceNow()
                                }
                            } else {
                                viewModel.turnOff()
                            }
                        },
                    )
                }
                Text(
                    "Shows the requests this device makes — decrypted locally, on this device only. " +
                        "Nothing captured here is ever sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.size(12.dp))

        ScrollCard(modifier = Modifier.fillMaxWidth(), showFlourish = false) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Screenshot, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Block screenshots & screen recording", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.screenshotProtectionEnabled,
                        onCheckedChange = viewModel::setScreenshotProtectionEnabled,
                    )
                }
                Text(
                    if (state.screenshotProtectionEnabled) {
                        "On by default: no other app can screenshot or screen-record this app, and it shows a blank Recents thumbnail. Turn this off if you need to capture your own findings — a repro, a report."
                    } else {
                        "Off: this app can be screenshotted and screen-recorded like any other, and shows normally in Recents. Turn this back on when you're done."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.size(12.dp))

        ScrollCard(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceVariant, showFlourish = false) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Why a certificate?", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "To read encrypted (HTTPS) traffic, the inspector needs a certificate it generated on " +
                        "this device installed into your device's trusted certificate list. It only lets " +
                        "this app decrypt traffic that already passes through this device's own local " +
                        "inspector — it's never uploaded, never shared, and never usable to intercept " +
                        "anyone else's traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        val intent = viewModel.prepareCertificateInstallIntent()
                        context.startActivity(intent)
                    }
                }) {
                    Text("Install the local certificate")
                }
            }
        }

        Spacer(modifier = Modifier.size(12.dp))

        ScrollCard(
            modifier = Modifier.fillMaxWidth(),
            showFlourish = false,
            onClick = { viewModel.setShowRewriteRules(true) },
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rewrite rules", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.rewriteRules.isEmpty()) {
                            "None yet — the inspector is read-only until you add one."
                        } else {
                            "${state.rewriteRules.count { it.enabled }} of ${state.rewriteRules.size} active"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { viewModel.setShowRewriteRules(true) }) { Text("Manage") }
            }
        }

        state.lastReplayResult?.let { message ->
            Spacer(modifier = Modifier.size(8.dp))
            // Same dismissable-status-banner shape as ChatScreen's
            // ErrorBanner and BrowserScreen's lastDownloadStarted message —
            // a plain Surface, not a Card: this is transient, minor chrome,
            // not a "major container" that warrants ScrollCard's border/
            // flourish treatment.
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissReplayResult) { Text("Dismiss") }
                }
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Captured requests", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.clearLog() }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear the captured traffic list")
            }
        }

        if (state.entries.isEmpty()) {
            EmptyTrafficState(inspectorEnabled = state.inspectorEnabled)
        } else {
            var now by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(15_000)
                    now = Instant.now()
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.entries.asReversed()) { entry ->
                    TrafficRow(
                        entry = entry,
                        now = now,
                        replaying = state.replayingUrl == entry.replay?.url,
                        onReplay = { viewModel.replay(entry) },
                        onViewBody = { viewModel.showBody(entry) },
                    )
                }
            }
        }
    }

    if (state.showRewriteRules) {
        RewriteRulesDialog(
            rules = state.rewriteRules,
            onSave = viewModel::saveRewriteRule,
            onDelete = viewModel::deleteRewriteRule,
            onSetEnabled = viewModel::setRewriteRuleEnabled,
            onDismiss = { viewModel.setShowRewriteRules(false) },
        )
    }

    state.viewingEntry?.let { entry ->
        TrafficBodyDialog(
            entry = entry,
            findStructureHint = viewModel::structureHintFor,
            onDismiss = viewModel::dismissBody,
        )
    }
}

@Composable
private fun EmptyTrafficState(inspectorEnabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text("Nothing captured yet", style = MaterialTheme.typography.titleSmall)
        Text(
            if (inspectorEnabled) {
                "The inspector is on. Browse to a site or use chat, and requests will show up here."
            } else {
                "Turn the inspector on above to start capturing this device's own traffic."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun TrafficRow(
    entry: TrafficEntry,
    now: Instant,
    replaying: Boolean,
    onReplay: () -> Unit,
    onViewBody: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.method,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    entry.host,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(TrafficFormat.statusLabel(entry.status), style = MaterialTheme.typography.labelMedium)
            }
            if (entry.path.isNotEmpty()) {
                Text(
                    entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TrafficFormat.humanBytes(entry.bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    TrafficFormat.relativeTime(entry.timestamp, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (entry.replay?.body?.isNotEmpty() == true) {
                    IconButton(onClick = onViewBody, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Code, contentDescription = "View request body", modifier = Modifier.size(18.dp))
                    }
                }
                if (entry.replay != null) {
                    if (replaying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onReplay, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Replay, contentDescription = "Replay this request", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A captured request's headers and body, with [structureHint] — the main
 * JSON array [InspectorViewModel.structureHintFor] found, if any —
 * called out above the body so a deeply nested payload doesn't have to
 * be found by eye. Body is pretty-printed when it parses as JSON, shown
 * as-is otherwise (plenty of captured bodies are form data, plain text,
 * or nothing this app can usefully reformat).
 */
@Composable
private fun TrafficBodyDialog(
    entry: TrafficEntry,
    findStructureHint: (host: String, body: String) -> JsonStructureScanner.Finding?,
    onDismiss: () -> Unit,
) {
    val bodyText = entry.replay?.body?.let { String(it, Charsets.UTF_8) } ?: ""
    val pretty = remember(bodyText) {
        runCatching { JSONObject(bodyText).toString(2) }.getOrNull()
            ?: runCatching { JSONArray(bodyText).toString(2) }.getOrNull()
            ?: bodyText
    }
    val structureHint = remember(entry.host, bodyText) {
        bodyText.takeIf { it.isNotEmpty() }?.let { findStructureHint(entry.host, it) }
    }

    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                // A raw method+host+path string is dense, punctuation-heavy
                // technical content (slashes, query params) on the app's
                // most trust-sensitive screen — not the short prominent
                // label Cinzel Decorative (titleSmall's usual font here) is
                // meant for. Keeps titleSmall's size/weight as this
                // dialog's header but swaps in the same monospace face the
                // body below already uses, instead of the decorative one.
                Text(
                    "${entry.method} ${entry.host}${entry.path}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                )
                structureHint?.let { hint ->
                    Text(
                        "Main array: ${hint.path} — ${hint.itemCount} item${if (hint.itemCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        pretty.ifEmpty { "(empty body)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text("Close") }
            }
        }
    }
}

/**
 * Rule management for the "control" half of the inspector: what's
 * active, an inline add-rule form, and a delete/enable toggle per rule.
 * A rule with neither a header action nor a body find/replace set is
 * accepted but does nothing — [com.evan.lazlo.proxy.net.RewriteEngine]
 * simply skips it — so a rule mid-edit here can't corrupt live traffic.
 */
@Composable
private fun RewriteRulesDialog(
    rules: List<RewriteRule>,
    onSave: (RewriteRule) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAddForm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        ScrollCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Rewrite rules", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Applied live to matching requests/responses as they pass through the inspector — set or remove a header, or find/replace text in the body.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (rules.isEmpty()) {
                    Text("No rules yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                } else {
                    rules.forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    rule.label.ifBlank { rule.hostContains.ifBlank { "Any host" } },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(ruleSummary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { onSetEnabled(rule.id, it) })
                            IconButton(onClick = { onDelete(rule.id) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete this rule")
                            }
                        }
                    }
                }

                if (showAddForm) {
                    AddRewriteRuleForm(
                        onSave = { rule ->
                            onSave(rule)
                            showAddForm = false
                        },
                        onCancel = { showAddForm = false },
                    )
                } else {
                    TextButton(onClick = { showAddForm = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("+ Add a rule")
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("Close") }
            }
        }
    }
}

private fun ruleSummary(rule: RewriteRule): String {
    val parts = buildList {
        if (!rule.setHeaderName.isNullOrBlank()) add("set ${rule.setHeaderName}")
        if (!rule.removeHeaderName.isNullOrBlank()) add("remove ${rule.removeHeaderName}")
        if (!rule.bodyFind.isNullOrEmpty()) add("replace body text")
        if (rule.appliesToRequest) add("on request")
        if (rule.appliesToResponse) add("on response")
    }
    return if (parts.isEmpty()) "Does nothing yet" else parts.joinToString(" · ")
}

@Composable
private fun AddRewriteRuleForm(onSave: (RewriteRule) -> Unit, onCancel: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var hostContains by remember { mutableStateOf("") }
    var appliesToRequest by remember { mutableStateOf(true) }
    var appliesToResponse by remember { mutableStateOf(false) }
    var setHeaderName by remember { mutableStateOf("") }
    var setHeaderValue by remember { mutableStateOf("") }
    var removeHeaderName by remember { mutableStateOf("") }
    var bodyFind by remember { mutableStateOf("") }
    var bodyReplace by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hostContains,
            onValueChange = { hostContains = it },
            label = { Text("Host contains (blank = any host)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            FilterChipLike("Request", appliesToRequest) { appliesToRequest = !appliesToRequest }
            Spacer(modifier = Modifier.size(8.dp))
            FilterChipLike("Response", appliesToResponse) { appliesToResponse = !appliesToResponse }
        }
        OutlinedTextField(
            value = setHeaderName,
            onValueChange = { setHeaderName = it },
            label = { Text("Set header name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = setHeaderValue,
            onValueChange = { setHeaderValue = it },
            label = { Text("Set header value") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = removeHeaderName,
            onValueChange = { removeHeaderName = it },
            label = { Text("Remove header name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = bodyFind,
            onValueChange = { bodyFind = it },
            label = { Text("Body: find text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = bodyReplace,
            onValueChange = { bodyReplace = it },
            label = { Text("Body: replace with") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(modifier = Modifier.padding(top = 12.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onSave(
                    RewriteRule(
                        id = java.util.UUID.randomUUID().toString(),
                        enabled = true,
                        label = label.trim(),
                        hostContains = hostContains.trim(),
                        appliesToRequest = appliesToRequest,
                        appliesToResponse = appliesToResponse,
                        setHeaderName = setHeaderName.trim().takeIf { it.isNotEmpty() },
                        setHeaderValue = setHeaderValue.takeIf { setHeaderName.isNotBlank() },
                        removeHeaderName = removeHeaderName.trim().takeIf { it.isNotEmpty() },
                        bodyFind = bodyFind.takeIf { it.isNotEmpty() },
                        bodyReplace = bodyReplace.takeIf { bodyFind.isNotEmpty() },
                    ),
                )
            }) {
                Text("Save")
            }
        }
    }
}

/** A minimal toggle chip — avoids pulling in FilterChip's full experimental API surface for two booleans. */
@Composable
private fun FilterChipLike(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
