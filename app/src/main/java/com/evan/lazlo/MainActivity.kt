package com.evan.lazlo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.evan.lazlo.ui.LazloApp
import com.evan.lazlo.ui.theme.LazloTheme

/**
 * Single-Activity host. All real UI lives under `ui/`, split by section
 * (chat / browser / inspector) and tied together by [LazloApp]'s bottom
 * navigation — this class only sets the Compose content and the theme.
 * See ARCHITECTURE.md for the module map each section wires into.
 *
 * FLAG_SECURE is set before any content is attached, covering the whole
 * app (chat transcripts, browsed pages, intercepted-traffic detail) for
 * as long as this single Activity is on screen: it blocks the standard
 * OS screenshot/screen-record paths (including another app's
 * MediaProjection capture), blanks the Recents/task-switcher thumbnail,
 * and stops non-secure external displays or casts from mirroring this
 * window. It does not and cannot stop a device already compromised by a
 * malicious Accessibility Service reading the view hierarchy directly —
 * no app-level flag can — but it closes every capture path a normal
 * app has available to it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            LazloTheme {
                LazloApp()
            }
        }
    }
}
