package com.evan.lazlo.browser

import android.content.Context
import com.google.android.play.core.ktx.bytesDownloaded
import com.google.android.play.core.ktx.errorCode
import com.google.android.play.core.ktx.moduleNames
import com.google.android.play.core.ktx.requestInstall
import com.google.android.play.core.ktx.requestProgressFlow
import com.google.android.play.core.ktx.status
import com.google.android.play.core.ktx.totalBytesToDownload
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
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
 * Turns a raw [SplitInstallErrorCode] into something a user can act on —
 * same "don't just print the number" discipline as
 * [com.evan.lazlo.ai.AiCoreDiagnosis]. Written for the two codes that
 * fire whenever the running build wasn't installed through the Play
 * Store — [SplitInstallErrorCode.APP_NOT_OWNED] and `PLAY_STORE_NOT_FOUND`
 * — since that's the *normal* case for a sideloaded pentesting-tool APK
 * like this one, not a rare misconfiguration worth a generic error code.
 */
internal object GeckoModuleDiagnosis {
    fun messageFor(errorCode: Int): String = when (errorCode) {
        SplitInstallErrorCode.APP_NOT_OWNED, SplitInstallErrorCode.PLAY_STORE_NOT_FOUND ->
            "GeckoView can't download this way on this install: Play Feature Delivery only works for " +
                "apps installed through the Play Store, and this build wasn't (normal for a sideloaded " +
                "APK). Chromium — already hardened against the WebView fingerprint sites detect — works " +
                "the same way without needing Play; or install this build via the Play Store or an " +
                "internal testing track to get real GeckoView delivery."
        SplitInstallErrorCode.NETWORK_ERROR ->
            "The download failed — check the network connection and try again."
        SplitInstallErrorCode.INSUFFICIENT_STORAGE ->
            "Not enough free storage to download GeckoView. Free up some space and try again."
        else -> "The download didn't complete (error $errorCode). Staying on the current engine."
    }
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

    /**
     * Kicks off installing the module; ongoing progress arrives via
     * [installState], not this call's own return value — this only
     * returns non-null when the request couldn't even be *started*, so
     * [installState] would never emit anything for it at all (nothing to
     * filter on: no session, no module name to match). That happens for
     * real, not just in theory — [SplitInstallErrorCode.APP_NOT_OWNED] /
     * `PLAY_STORE_NOT_FOUND` fire whenever the running build wasn't
     * installed through the Play Store, which is exactly how a sideloaded
     * debug or pentesting-tool build (this app) is commonly tested. Caught
     * here, not left to propagate: this runs automatically now that
     * GeckoView is the default engine (see BrowserViewModel's init
     * block), not just from an explicit "switch engine" tap, so an
     * uncaught [SplitInstallException] here would crash the Browser tab
     * on open for anyone running exactly that kind of build — not a rare
     * unhappy path anymore.
     */
    suspend fun requestInstall(): GeckoModuleState.Failed? {
        if (isGeckoModuleInstalled()) return null
        return runCatching { splitInstallManager.requestInstall(listOf(GECKO_ENGINE_MODULE)) }
            .fold(
                onSuccess = { null },
                onFailure = { t ->
                    val errorCode = (t as? SplitInstallException)?.errorCode ?: SplitInstallErrorCode.INTERNAL_ERROR
                    GeckoModuleState.Failed(errorCode)
                },
            )
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
