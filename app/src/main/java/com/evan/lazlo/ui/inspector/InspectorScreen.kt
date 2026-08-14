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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.evan.lazlo.proxy.TrafficEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        Card(modifier = Modifier.fillMaxWidth()) {
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

        Card(modifier = Modifier.fillMaxWidth()) {
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

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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
                    TrafficRow(entry = entry, now = now)
                }
            }
        }
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
private fun TrafficRow(entry: TrafficEntry, now: Instant) {
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
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    TrafficFormat.humanBytes(entry.bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    TrafficFormat.relativeTime(entry.timestamp, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
