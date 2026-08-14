package com.evan.lazlo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.evan.lazlo.core.Settings
import com.evan.lazlo.ui.LazloApp
import com.evan.lazlo.ui.theme.LazloTheme
import kotlinx.coroutines.launch

/**
 * Single-Activity host. All real UI lives under `ui/`, split by section
 * (chat / browser / inspector) and tied together by [LazloApp]'s bottom
 * navigation — this class only sets the Compose content and the theme.
 * See ARCHITECTURE.md for the module map each section wires into.
 *
 * FLAG_SECURE — set synchronously before any content is attached, so
 * the very first frame is already covered — blocks the standard OS
 * screenshot/screen-record paths (including another app's
 * MediaProjection capture), blanks the Recents/task-switcher thumbnail,
 * and stops non-secure external displays or casts from mirroring this
 * window. It does not and cannot stop a device already compromised by a
 * malicious Accessibility Service reading the view hierarchy directly —
 * no app-level flag can — but it closes every capture path a normal
 * app has available to it.
 *
 * This is a real toggle (`Settings.screenshotProtectionFlow`, surfaced
 * in the Inspector tab), not a hard lock: it starts on for safety, but
 * a user doing security research who needs to screenshot or record
 * their own findings can turn it off, and this collector applies that
 * choice live — no restart needed either way.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            Settings(applicationContext).screenshotProtectionFlow().collect { enabled ->
                if (enabled) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            LazloTheme {
                LazloApp()
            }
        }
    }
}
