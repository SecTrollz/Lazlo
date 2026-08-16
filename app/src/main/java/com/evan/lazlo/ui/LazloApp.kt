package com.evan.lazlo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.evan.lazlo.ui.browser.BrowserScreen
import com.evan.lazlo.ui.chat.ChatScreen
import com.evan.lazlo.ui.inspector.InspectorScreen
import com.evan.lazlo.ui.theme.CornerFlourish
import com.evan.lazlo.ui.theme.FlourishCorner

/** The three top-level sections, in bottom-nav order. */
enum class LazloTab(val label: String, val icon: ImageVector, val subtitle: String) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat, "Chat"),
    BROWSER("Browser", Icons.Filled.Public, "Browser"),
    INSPECTOR("Inspector", Icons.Filled.Shield, "Traffic inspector"),
}

/**
 * App-level scaffold: a branded top bar, the three sections switched by
 * a bottom [NavigationBar], each section built from its own screen file
 * under `ui/`. Each screen owns and persists its own state through its
 * ViewModel, so switching tabs doesn't lose the address bar text, the
 * chat transcript, or the traffic log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazloApp() {
    var selectedTab by rememberSaveable { mutableStateOf(LazloTab.CHAT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // A single small flourish beside the wordmark — the
                    // one place this reskin's ornament shows up on every
                    // screen rather than just on dialogs/cards — kept to
                    // one corner-sized mark so it reads as a margin
                    // doodle next to the title, not a logo of its own.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CornerFlourish(
                            corner = FlourishCorner.TopStart,
                            modifier = Modifier.padding(end = 6.dp),
                            size = 18.dp,
                        )
                        Text("Lazlo — ${selectedTab.subtitle}")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                LazloTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                LazloTab.CHAT -> ChatScreen()
                LazloTab.BROWSER -> BrowserScreen()
                LazloTab.INSPECTOR -> InspectorScreen()
            }
            // Suppressed on the Inspector tab itself — InspectorScreen
            // already shows this same status live there, so a second copy
            // floating on top of it would just be redundant chrome.
            if (selectedTab != LazloTab.INSPECTOR) {
                InspectorPillOverlay(
                    onOpenInspectorTab = { selectedTab = LazloTab.INSPECTOR },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}
