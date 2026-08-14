package com.evan.lazlo.browser

import android.content.Context
import com.google.android.play.core.ktx.bytesDownloaded
import com.google.android.play.core.ktx.errorCode
import com.google.android.play.core.ktx.moduleNames
import com.google.android.play.core.ktx.requestInstall
import com.google.android.play.core.ktx.requestProgressFlow
import com.google.android.play.core.ktx.status
import com.google.android.play.core.ktx.totalBytesToDownload
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The module's distribution name, i.e. what [SplitInstallManager] and
 * [SplitInstallSessionState.moduleNames] use to refer to it — the
 * Gradle project's logical name (`gecko_engine`, not the `gecko-engine`
 * directory it lives in: Android feature module names can't contain
 * hyphens, only letters/digits/underscores; see settings.gradle.kts).
 */
private const val GECKO_ENGINE_MODULE = "gecko_engine"
private const val GECKO_ENGINE_CLASS = "com.evan.lazlo.browser.GeckoEngine"

sealed class GeckoModuleState {
    data object NotInstalled : GeckoModuleState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : GeckoModuleState()
    data object Installing : GeckoModuleState()
    data object Installed : GeckoModuleState()
    data class RequiresConfirmation(val state: SplitInstallSessionState) : GeckoModuleState()
    data class Failed(val errorCode: Int) : GeckoModuleState()
}

/**
 * Loads [GeckoEngine] from its dynamic feature module
 * (`dynamic-features/gecko-engine/`) on demand via Play Feature
 * Delivery. The app module has no compile-time dependency on that class
 * at all — that's the entire point of splitting it out per
 * ARCHITECTURE.md — so it's instantiated through reflection once Play
 * confirms the module is installed, which is the standard pattern for
 * "base module defines the interface, feature module provides the
 * implementation."
 *
 * Verification note: [GECKO_ENGINE_MODULE] is confirmed correct at build
 * time (a wrong name here fails `generateDebugFeatureMetadata` outright,
 * and it doesn't), but the actual SplitInstall flow — a real install
 * request, progress updates, the confirmation-dialog path — can only be
 * exercised on a real device or through Play's internal testing track;
 * it isn't something a build sandbox without Play Store infrastructure
 * can run.
 */
class BrowserEngineLoader(private val context: Context) {

    private val splitInstallManager: SplitInstallManager = SplitInstallManagerFactory.create(context)

    fun isGeckoModuleInstalled(): Boolean = GECKO_ENGINE_MODULE in splitInstallManager.installedModules

    /** Progress/outcome of a module install already in flight, filtered to just this module's updates. */
    fun installState(): Flow<GeckoModuleState> =
        splitInstallManager.requestProgressFlow()
            .filter { GECKO_ENGINE_MODULE in it.moduleNames }
            .map { it.toGeckoModuleState() }

    /** Kicks off installing the module; progress arrives via [installState] rather than this call's own return value. */
    suspend fun requestInstall() {
        if (isGeckoModuleInstalled()) return
        splitInstallManager.requestInstall(listOf(GECKO_ENGINE_MODULE))
    }

    /** Call after a [GeckoModuleState.RequiresConfirmation] to show Play's own confirmation UI (e.g. for a large download over cellular). */
    fun confirmInstall(state: SplitInstallSessionState, activity: android.app.Activity, requestCode: Int) {
        splitInstallManager.startConfirmationDialogForResult(state, activity, requestCode)
    }

    /** Instantiates [GeckoEngine] via reflection — the app module can't reference it directly since it lives in the dynamic feature module. */
    fun newGeckoEngine(): BrowserEngine {
        val clazz = Class.forName(GECKO_ENGINE_CLASS)
        val constructor = clazz.getConstructor(Context::class.java)
        return constructor.newInstance(context) as BrowserEngine
    }

    private fun SplitInstallSessionState.toGeckoModuleState(): GeckoModuleState = when (status) {
        SplitInstallSessionStatus.DOWNLOADING -> GeckoModuleState.Downloading(bytesDownloaded, totalBytesToDownload)
        SplitInstallSessionStatus.INSTALLING, SplitInstallSessionStatus.DOWNLOADED -> GeckoModuleState.Installing
        SplitInstallSessionStatus.INSTALLED -> GeckoModuleState.Installed
        SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> GeckoModuleState.RequiresConfirmation(this)
        SplitInstallSessionStatus.FAILED -> GeckoModuleState.Failed(errorCode)
        else -> GeckoModuleState.NotInstalled
    }
}
