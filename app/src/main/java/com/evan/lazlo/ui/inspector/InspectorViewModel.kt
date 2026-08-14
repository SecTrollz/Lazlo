package com.evan.lazlo.ui.inspector

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evan.lazlo.core.Settings
import com.evan.lazlo.proxy.CertificateAuthority
import com.evan.lazlo.proxy.MitmVpnService
import com.evan.lazlo.proxy.TrafficEntry
import com.evan.lazlo.proxy.TrafficLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InspectorUiState(
    val inspectorEnabled: Boolean = false,
    val entries: List<TrafficEntry> = emptyList(),
    /** True while the CA is being generated/loaded and the VPN consent flow is being kicked off. */
    val isPreparing: Boolean = false,
)

class InspectorViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    private val _uiState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(inspectorEnabled = settings.inspectorEnabled()) }
        }
        viewModelScope.launch {
            TrafficLog.entries.collect { entries -> _uiState.update { it.copy(entries = entries) } }
        }
    }

    /**
     * Persists the toggle as "on" and makes sure the local CA exists —
     * off the main thread, since generating a fresh 4096-bit Keystore
     * key is real CPU work, not a cheap prefs write. Returns the OS's
     * VPN-consent [Intent] to launch if the user hasn't already granted
     * it this install, or null if the service can start immediately.
     */
    suspend fun turnOn(): Intent? {
        settings.setInspectorEnabled(true)
        _uiState.update { it.copy(inspectorEnabled = true, isPreparing = true) }
        val app = getApplication<Application>()
        withContext(Dispatchers.Default) { CertificateAuthority(app).ensureCaExists() }
        _uiState.update { it.copy(isPreparing = false) }
        return VpnService.prepare(app)
    }

    /** Actually starts the inspector service — call once VPN consent is confirmed (or wasn't needed). */
    fun startServiceNow() {
        val app = getApplication<Application>()
        app.startService(Intent(app, MitmVpnService::class.java))
    }

    /** The user declined the OS VPN-consent dialog: reflect that the inspector isn't actually running. */
    fun onVpnConsentDenied() {
        viewModelScope.launch { settings.setInspectorEnabled(false) }
        _uiState.update { it.copy(inspectorEnabled = false, isPreparing = false) }
    }

    fun turnOff() {
        viewModelScope.launch { settings.setInspectorEnabled(false) }
        _uiState.update { it.copy(inspectorEnabled = false) }
        val app = getApplication<Application>()
        app.stopService(Intent(app, MitmVpnService::class.java))
    }

    /** Builds the OS's "install this certificate" intent, generating the CA first if needed (off the main thread). */
    suspend fun prepareCertificateInstallIntent(): Intent {
        val app = getApplication<Application>()
        val ca = CertificateAuthority(app)
        val cert = withContext(Dispatchers.Default) { ca.ensureCaExists() }
        return ca.installIntent(cert)
    }

    fun clearLog() {
        TrafficLog.clear()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InspectorViewModel(application) as T
    }
}
