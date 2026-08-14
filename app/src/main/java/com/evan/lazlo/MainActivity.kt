package com.evan.lazlo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.evan.lazlo.ui.LazloApp
import com.evan.lazlo.ui.theme.LazloTheme

/**
 * Single-Activity host. All real UI lives under `ui/`, split by section
 * (chat / browser / inspector) and tied together by [LazloApp]'s bottom
 * navigation — this class only sets the Compose content and the theme.
 * See ARCHITECTURE.md for the module map each section wires into.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LazloTheme {
                LazloApp()
            }
        }
    }
}
