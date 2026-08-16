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
import com.evan.lazlo.proxy.RewriteRuleStore
import com.evan.lazlo.proxy.TrafficEntry
import com.evan.lazlo.proxy.TrafficLog
import com.evan.lazlo.proxy.net.RewriteRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class InspectorUiState(
    val inspectorEnabled: Boolean = false,
    val entries: List<TrafficEntry> = emptyList(),
    /** True while the CA is being generated/loaded and the VPN consent flow is being kicked off. */
    val isPreparing: Boolean = false,
    /** Mirrors Settings.screenshotProtectionFlow; MainActivity applies this to the window independently. */
    val screenshotProtectionEnabled: Boolean = true,
    val rewriteRules: List<RewriteRule> = emptyList(),
    val showRewriteRules: Boolean = false,
    val replayingUrl: String? = null,
    /** One-shot "Replayed — HTTP 200" / "Replay failed: ..." banner; cleared once shown. */
    val lastReplayResult: String? = null,
    /** Non-null while the "view body" dialog is open for this entry. */
    val viewingEntry: TrafficEntry? = null,
    /** Mirrors Settings.pillPrivacyFlow; InspectorPillOverlay masks its live numbers while this is on. */
    val pillPrivacyEnabled: Boolean = false,
)

/** Headers OkHttp derives itself from the URL/body — copying the originally-captured values for these onto a replayed request would either conflict with what OkHttp computes or just be wrong for a resend (a stale Content-Length after a hand-edited body, a Host that no longer matches). */
private val REPLAY_SKIPPED_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")

class InspectorViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val rewriteRuleStore = RewriteRuleStore(application)
    private val replayClient = OkHttpClient()
    private val jsonPathCache = LearnedJsonPathCache()

    private val _uiState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(inspectorEnabled = settings.inspectorEnabled()) }
        }
        viewModelScope.launch {
            TrafficLog.entries.collect { entries -> _uiState.update { it.copy(entries = entries) } }
        }
        viewModelScope.launch {
            settings.screenshotProtectionFlow().collect { enabled ->
                _uiState.update { it.copy(screenshotProtectionEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            rewriteRuleStore.rules().collect { rules -> _uiState.update { it.copy(rewriteRules = rules) } }
        }
        viewModelScope.launch {
            settings.pillPrivacyFlow().collect { enabled -> _uiState.update { it.copy(pillPrivacyEnabled = enabled) } }
        }
    }

    fun setPillPrivacyEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setPillPrivacyEnabled(enabled) }
    }

    fun setScreenshotProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setScreenshotProtectionEnabled(enabled) }
    }

    // --- Rewrite rules: the "control" half of the inspector ------------

    fun setShowRewriteRules(show: Boolean) = _uiState.update { it.copy(showRewriteRules = show) }

    fun saveRewriteRule(rule: RewriteRule) {
        viewModelScope.launch { rewriteRuleStore.addOrUpdate(rule) }
    }

    fun deleteRewriteRule(id: String) {
        viewModelScope.launch { rewriteRuleStore.remove(id) }
    }

    fun setRewriteRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { rewriteRuleStore.setEnabled(id, enabled) }
    }

    // --- Replay ----------------------------------------------------------

    /**
     * Resends a captured request exactly as [TrafficEntry.replay] holds
     * it, over this app's own normal network stack (OkHttp) — not
     * through [com.evan.lazlo.proxy.net.TcpIpStack] directly, so if the
     * inspector is on and its CA is trusted, the replay's own request and
     * response show up in the traffic log too, the same as any other
     * request this app makes.
     */
    fun replay(entry: TrafficEntry) {
        val replayable = entry.replay ?: return
        if (_uiState.value.replayingUrl != null) return
        _uiState.update { it.copy(replayingUrl = replayable.url) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val method = replayable.method.uppercase()
                    val contentType = replayable.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                    val needsBody = method in setOf("POST", "PUT", "PATCH", "DELETE")
                    val body = when {
                        replayable.body.isNotEmpty() -> replayable.body.toRequestBody(contentType?.toMediaTypeOrNull())
                        needsBody -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
                        else -> null
                    }
                    val requestBuilder = Request.Builder().url(replayable.url).method(method, body)
                    replayable.headers.forEach { (name, value) ->
                        if (name.lowercase() !in REPLAY_SKIPPED_HEADERS) requestBuilder.header(name, value)
                    }
                    replayClient.newCall(requestBuilder.build()).execute().use { it.code }
                }
            }
            _uiState.update {
                it.copy(
                    replayingUrl = null,
                    lastReplayResult = outcome.fold(
                        onSuccess = { code -> "Replayed — HTTP $code" },
                        onFailure = { e -> "Replay failed: ${e.message ?: e::class.simpleName}" },
                    ),
                )
            }
        }
    }

    fun dismissReplayResult() = _uiState.update { it.copy(lastReplayResult = null) }

    // --- Body viewer -------------------------------------------------

    fun showBody(entry: TrafficEntry) = _uiState.update { it.copy(viewingEntry = entry) }
    fun dismissBody() = _uiState.update { it.copy(viewingEntry = null) }

    /**
     * The "main array" hint [TrafficBodyDialog] shows above a JSON body,
     * cache-first: a cached path for [host] is trusted only if it still
     * resolves to an array in this [body] (a host can start returning a
     * different shape — a new endpoint, an API version bump — and a
     * stale cached path pointing at nothing would be worse than no hint
     * at all). Falls back to a fresh [JsonStructureScanner.scan] on a
     * cache miss or a path that no longer resolves, and remembers
     * whatever that scan finds for next time.
     */
    fun structureHintFor(host: String, body: String): JsonStructureScanner.Finding? {
        jsonPathCache.get(host)?.let { cached ->
            val length = JsonStructureScanner.resolveArrayLength(body, cached.path)
            if (length != null) return cached.copy(itemCount = length)
            jsonPathCache.forget(host)
        }
        return JsonStructureScanner.scan(body)?.also { jsonPathCache.learn(host, it) }
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
