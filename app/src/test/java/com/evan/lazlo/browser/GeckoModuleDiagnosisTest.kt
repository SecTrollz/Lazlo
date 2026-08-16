package com.evan.lazlo.browser

import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the exact real-world crash this class exists to prevent from
 * being a dead-end: GeckoView becoming the default engine means
 * [BrowserEngineLoader.requestInstall] now runs automatically on every
 * Browser tab open, not just from an explicit "switch engine" tap — and
 * [SplitInstallErrorCode.APP_NOT_OWNED] / `PLAY_STORE_NOT_FOUND` fire on
 * every single one of those automatic attempts for a build that wasn't
 * installed through the Play Store, which is the normal way a sideloaded
 * pentesting-tool APK (this app) gets tested.
 */
class GeckoModuleDiagnosisTest {

    @Test
    fun `APP_NOT_OWNED is diagnosed as a sideload, not a generic failure`() {
        val message = GeckoModuleDiagnosis.messageFor(SplitInstallErrorCode.APP_NOT_OWNED)
        assertTrue(message.contains("Play Store", ignoreCase = true))
        assertTrue(message.contains("Chromium", ignoreCase = true))
    }

    @Test
    fun `PLAY_STORE_NOT_FOUND gets the same sideload diagnosis as APP_NOT_OWNED`() {
        val message = GeckoModuleDiagnosis.messageFor(SplitInstallErrorCode.PLAY_STORE_NOT_FOUND)
        assertTrue(message.contains("Play Store", ignoreCase = true))
    }

    @Test
    fun `network errors name the actual fix`() {
        val message = GeckoModuleDiagnosis.messageFor(SplitInstallErrorCode.NETWORK_ERROR)
        assertTrue(message.contains("network", ignoreCase = true))
    }

    @Test
    fun `storage errors name the actual fix`() {
        val message = GeckoModuleDiagnosis.messageFor(SplitInstallErrorCode.INSUFFICIENT_STORAGE)
        assertTrue(message.contains("storage", ignoreCase = true))
    }

    @Test
    fun `unknown codes fall back to a generic message without crashing`() {
        val message = GeckoModuleDiagnosis.messageFor(-999)
        assertTrue(message.contains("-999"))
    }
}
