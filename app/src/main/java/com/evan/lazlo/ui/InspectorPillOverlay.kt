package com.evan.lazlo.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evan.lazlo.proxy.TrafficEntry
import com.evan.lazlo.proxy.net.RewriteRule
import com.evan.lazlo.ui.inspector.InspectorUiState
import com.evan.lazlo.ui.inspector.InspectorViewModel
import com.evan.lazlo.ui.inspector.TrafficFormat
import com.evan.lazlo.ui.theme.ScrollCard
import java.time.Instant

private enum class PillTab(val label: String) { STATUS("Status"), RULES("Rules"), LOG("Log") }

/**
 * A permanent, compact status readout for the traffic inspector, with a
 * settings sheet that slides up from it when tapped — glanceable at a
 * fixed size always, not a full panel eating screen space until you ask
 * for more. Mounted once at [LazloApp] level (not inside
 * [com.evan.lazlo.ui.inspector.InspectorScreen]) so it's visible while
 * browsing or chatting too: the point is not needing to switch to the
 * Inspector tab just to see "is this still capturing" or catch what just
 * went by.
 *
 * Deliberately scoped to [InspectorViewModel] alone, never
 * `BrowserViewModel` — `BrowserViewModel`'s init block can kick off a
 * real GeckoView module download the moment it's constructed (see its
 * own doc comment), and this overlay is composed unconditionally
 * regardless of which tab is active. Eagerly creating `BrowserViewModel`
 * here would mean that download fires before the user ever opens the
 * Browser tab. `InspectorViewModel`'s own init is passive collection
 * only, so mounting it app-wide has no such side effect.
 */
@Composable
fun InspectorPillOverlay(
    onOpenInspectorTab: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InspectorViewModel = viewModel(
        factory = InspectorViewModel.Factory(LocalContext.current.applicationContext as Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    var paneOpen by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(PillTab.STATUS) }

    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = paneOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            PillSettingsPane(
                state = state,
                activeTab = activeTab,
                onSelectTab = { activeTab = it },
                onTogglePrivacy = viewModel::setPillPrivacyEnabled,
                onSetRuleEnabled = viewModel::setRewriteRuleEnabled,
                onManageRules = {
                    paneOpen = false
                    onOpenInspectorTab()
                },
                onViewAllLog = {
                    paneOpen = false
                    onOpenInspectorTab()
                },
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        MiniBar(
            entryCount = state.entries.size,
            inspectorEnabled = state.inspectorEnabled,
            privacyMode = state.pillPrivacyEnabled,
            paneOpen = paneOpen,
            onClick = { paneOpen = !paneOpen },
        )
    }
}

/** Masks a number as "••" when [privacyMode] is on — same fixed-width masking as [maskHost], so the pill's shape doesn't change with or without it. */
private fun maskCount(count: Int, privacyMode: Boolean): String = if (privacyMode) "••" else count.toString()

/** Masks a host/URL string as "••••" when [privacyMode] is on. */
private fun maskHost(text: String, privacyMode: Boolean): String = if (privacyMode) "••••" else text

@Composable
private fun MiniBar(
    entryCount: Int,
    inspectorEnabled: Boolean,
    privacyMode: Boolean,
    paneOpen: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.widthIn(max = 220.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (inspectorEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                if (inspectorEnabled) "${maskCount(entryCount, privacyMode)} captured" else "Inspector off",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Icon(
                if (paneOpen) Icons.Filled.VisibilityOff else Icons.Filled.Settings,
                contentDescription = if (paneOpen) "Close inspector panel" else "Open inspector panel",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PillSettingsPane(
    state: InspectorUiState,
    activeTab: PillTab,
    onSelectTab: (PillTab) -> Unit,
    onTogglePrivacy: (Boolean) -> Unit,
    onSetRuleEnabled: (String, Boolean) -> Unit,
    onManageRules: () -> Unit,
    onViewAllLog: () -> Unit,
) {
    ScrollCard(modifier = Modifier.widthIn(max = 320.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PillTab.entries.forEach { tab ->
                    TextButton(onClick = { onSelectTab(tab) }) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tab == activeTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (activeTab) {
                PillTab.STATUS -> StatusTab(state, onTogglePrivacy)
                PillTab.RULES -> RulesTab(state.rewriteRules, onSetRuleEnabled, onManageRules)
                PillTab.LOG -> LogTab(state.entries, state.pillPrivacyEnabled, onViewAllLog)
            }
        }
    }
}

@Composable
private fun StatusTab(state: InspectorUiState, onTogglePrivacy: (Boolean) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.inspectorEnabled) "Capturing traffic" else "Inspector is off",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${maskCount(state.entries.size, state.pillPrivacyEnabled)} requests captured this session",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (state.pillPrivacyEnabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("Privacy mode", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = state.pillPrivacyEnabled, onCheckedChange = onTogglePrivacy)
        }
        Text(
            "Masks the count and hosts shown here to \"••\" — for glancing at status on a call or screen share.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RulesTab(rules: List<RewriteRule>, onSetEnabled: (String, Boolean) -> Unit, onManageRules: () -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (rules.isEmpty()) {
            Text(
                "No rewrite rules yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rules.forEach { rule ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        rule.label.ifBlank { rule.hostContains.ifBlank { "Any host" } },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onSetEnabled(rule.id, it) },
                        modifier = Modifier.size(width = 36.dp, height = 20.dp),
                    )
                }
            }
        }
        TextButton(onClick = onManageRules, modifier = Modifier.padding(top = 4.dp)) {
            Text("Manage rules")
        }
    }
}

@Composable
private fun LogTab(entries: List<TrafficEntry>, privacyMode: Boolean, onViewAll: () -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        val recent = entries.asReversed().take(5)
        if (recent.isEmpty()) {
            Text(
                "Nothing captured yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val now = remember { Instant.now() }
            recent.forEach { entry ->
                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ListIcon, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        "${entry.method} ${maskHost(entry.host, privacyMode)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        TrafficFormat.relativeTime(entry.timestamp, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = onViewAll, modifier = Modifier.padding(top = 4.dp)) {
            Text("Open full inspector")
        }
    }
}
