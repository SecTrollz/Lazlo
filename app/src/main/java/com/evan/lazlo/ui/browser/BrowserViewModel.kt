package com.evan.lazlo.ui.browser

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evan.lazlo.browser.BrowserEngineLoader
import com.evan.lazlo.browser.BrowserScript
import com.evan.lazlo.browser.BrowserScriptStore
import com.evan.lazlo.browser.DownloadRequest
import com.evan.lazlo.browser.EngineKind
import com.evan.lazlo.browser.GeckoModuleState
import com.evan.lazlo.core.BrowserDataStore
import com.evan.lazlo.core.BrowserRecord
import com.evan.lazlo.core.DownloadRecord
import com.evan.lazlo.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One open tab. Tabs are lightweight (a URL + a title), not independent
 * live engine sessions: switching tabs re-navigates the single shared
 * engine instance rather than keeping N native WebView/GeckoView
 * instances alive at once — the same trade-off this codebase already
 * makes for engine switching (see BrowserScreen's doc comment), extended
 * to tabs for the same reason: GeckoView sessions in particular are
 * expensive to keep resident, and this app doesn't need true per-tab
 * process isolation to be a real multi-tab browser.
 */
data class BrowserTab(val id: String, val url: String, val title: String = "")

data class BrowserUiState(
    val engineKind: EngineKind = EngineKind.CHROMIUM,
    val addressBarText: String = "",
    val currentUrl: String? = null,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val showEnginePicker: Boolean = false,
    /** Non-null only while switching to GeckoView requires downloading its dynamic feature module first. */
    val geckoModuleState: GeckoModuleState? = null,
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String = "",
    val showHistory: Boolean = false,
    val showBookmarks: Boolean = false,
    val showDownloads: Boolean = false,
    val history: List<BrowserRecord> = emptyList(),
    val bookmarks: List<BrowserRecord> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    /** One-shot "Downloading <file>" confirmation; cleared once shown. */
    val lastDownloadStarted: String? = null,
    val scripts: List<BrowserScript> = emptyList(),
    val showScripts: Boolean = false,
    /** Non-null while the script editor dialog is open — an existing script being edited, or a blank one for "New script". */
    val editingScript: BrowserScript? = null,
) {
    val activeTab: BrowserTab? get() = tabs.find { it.id == activeTabId }
    val isCurrentPageBookmarked: Boolean get() = currentUrl != null && bookmarks.any { it.url == currentUrl }
}

private const val START_PAGE = "https://duckduckgo.com/"

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val browserDataStore = BrowserDataStore(application)
    private val scriptStore = BrowserScriptStore(application)
    val browserEngineLoader = BrowserEngineLoader(application)

    private val initialTab = BrowserTab(id = UUID.randomUUID().toString(), url = START_PAGE)
    private val _uiState = MutableStateFlow(
        BrowserUiState(addressBarText = START_PAGE, tabs = listOf(initialTab), activeTabId = initialTab.id),
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val kind = settings.browserEngine()
            if (kind == EngineKind.GECKO && !browserEngineLoader.isGeckoModuleInstalled()) {
                // GeckoView is the default (see Settings.browserEngine) but
                // its module hasn't downloaded yet — first-ever launch, a
                // fresh install, cleared data, or the OS reclaiming an
                // unused split. Render on Chromium/WebView only as a
                // stopgap for this one screen while the real engine
                // fetches itself in the background — the whole point of
                // making Gecko the default is that WebView's fingerprint
                // gets sites banned, so this shouldn't silently settle for
                // it long-term the way it used to.
                _uiState.update { it.copy(engineKind = EngineKind.CHROMIUM) }
                startGeckoModuleInstall()
            } else {
                _uiState.update { it.copy(engineKind = kind) }
            }
        }
        viewModelScope.launch {
            browserDataStore.history().collect { entries -> _uiState.update { it.copy(history = entries) } }
        }
        viewModelScope.launch {
            browserDataStore.bookmarks().collect { entries -> _uiState.update { it.copy(bookmarks = entries) } }
        }
        viewModelScope.launch {
            browserDataStore.downloads().collect { entries -> _uiState.update { it.copy(downloads = entries) } }
        }
        viewModelScope.launch {
            scriptStore.scripts().collect { entries -> _uiState.update { it.copy(scripts = entries) } }
        }
    }

    /** The page a freshly-(re)created engine instance should load — the active tab's last known URL, or a sensible start page. */
    fun startUrl(): String = _uiState.value.currentUrl ?: START_PAGE

    fun setEngine(kind: EngineKind) {
        if (kind == _uiState.value.engineKind) {
            _uiState.update { it.copy(showEnginePicker = false) }
            return
        }
        _uiState.update { it.copy(showEnginePicker = false) }

        if (kind == EngineKind.GECKO && !browserEngineLoader.isGeckoModuleInstalled()) {
            startGeckoModuleInstall()
            return
        }

        applyEngine(kind)
    }

    private fun applyEngine(kind: EngineKind) {
        viewModelScope.launch { settings.setBrowserEngine(kind) }
        _uiState.update { it.copy(engineKind = kind, geckoModuleState = null) }
    }

    private fun startGeckoModuleInstall() {
        _uiState.update { it.copy(geckoModuleState = GeckoModuleState.NotInstalled) }
        viewModelScope.launch {
            val progressJob = launch {
                browserEngineLoader.installState().collect { state ->
                    _uiState.update { it.copy(geckoModuleState = state) }
                    if (state is GeckoModuleState.Installed) applyEngine(EngineKind.GECKO)
                }
            }
            // Non-null only if the request couldn't even start (see
            // requestInstall()'s doc comment) — installState() will never
            // emit anything for a request that never got a session, so
            // this is the only place that failure surfaces from.
            browserEngineLoader.requestInstall()?.let { failure ->
                progressJob.cancel()
                _uiState.update { it.copy(geckoModuleState = failure) }
            }
        }
    }

    fun dismissGeckoModuleInstall() {
        _uiState.update { it.copy(geckoModuleState = null) }
    }

    fun setEnginePickerVisible(visible: Boolean) {
        _uiState.update { it.copy(showEnginePicker = visible) }
    }

    fun onAddressBarTextChange(text: String) {
        _uiState.update { it.copy(addressBarText = text) }
    }

    /** Resolves the current address bar text into a URL to load, per [UrlBarInput]. */
    fun resolveSubmittedAddress(): String = UrlBarInput.resolve(_uiState.value.addressBarText)

    fun onNavigationStateChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url,
                addressBarText = url,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                tabs = state.tabs.map { if (it.id == state.activeTabId) it.copy(url = url) else it },
            )
        }
        val title = _uiState.value.activeTab?.title?.takeIf { it.isNotBlank() } ?: url
        viewModelScope.launch { browserDataStore.recordVisit(url, title) }
    }

    fun onTitleChanged(title: String) {
        if (title.isBlank()) return
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == state.activeTabId) it.copy(title = title) else it })
        }
        _uiState.value.currentUrl?.let { url ->
            viewModelScope.launch { browserDataStore.recordVisit(url, title) }
        }
    }

    fun onLoadingChanged(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }

    // --- Tabs ---------------------------------------------------------

    /** Opens a fresh tab and switches to it; returns the URL the engine should load. */
    fun newTab(): String {
        val tab = BrowserTab(id = UUID.randomUUID().toString(), url = START_PAGE)
        _uiState.update {
            it.copy(
                tabs = it.tabs + tab,
                activeTabId = tab.id,
                addressBarText = START_PAGE,
                currentUrl = null,
                canGoBack = false,
                canGoForward = false,
            )
        }
        return START_PAGE
    }

    /** Closes tab [id]. Returns the URL the engine should load if the *active* tab changed as a result, null otherwise (including when there's only one tab left — never closes the last one). */
    fun closeTab(id: String): String? {
        val state = _uiState.value
        if (state.tabs.size <= 1) return null
        val wasActive = state.activeTabId == id
        val remaining = state.tabs.filterNot { it.id == id }
        val newActive = if (wasActive) remaining.last() else state.tabs.first { it.id == state.activeTabId }
        _uiState.update {
            it.copy(
                tabs = remaining,
                activeTabId = newActive.id,
                addressBarText = newActive.url,
                currentUrl = newActive.url,
            )
        }
        return if (wasActive) newActive.url else null
    }

    /** Switches to tab [id]. Returns the URL the engine should load, or null if [id] is already active/unknown. */
    fun switchTab(id: String): String? {
        val state = _uiState.value
        if (id == state.activeTabId) return null
        val tab = state.tabs.find { it.id == id } ?: return null
        _uiState.update { it.copy(activeTabId = id, addressBarText = tab.url, currentUrl = tab.url) }
        return tab.url
    }

    // --- Bookmarks / history -------------------------------------------

    fun toggleBookmark() {
        val tab = _uiState.value.activeTab ?: return
        if (tab.url.isBlank()) return
        viewModelScope.launch { browserDataStore.toggleBookmark(tab.url, tab.title.ifBlank { tab.url }) }
    }

    fun setShowHistory(show: Boolean) = _uiState.update { it.copy(showHistory = show) }
    fun setShowBookmarks(show: Boolean) = _uiState.update { it.copy(showBookmarks = show) }
    fun setShowDownloads(show: Boolean) = _uiState.update { it.copy(showDownloads = show) }

    fun clearHistory() {
        viewModelScope.launch { browserDataStore.clearHistory() }
    }

    /** Loads [url] into the current tab and closes any open history/bookmarks dialog. Caller still needs to tell the engine to navigate. */
    fun openUrlInCurrentTab(url: String) {
        _uiState.update {
            it.copy(
                showHistory = false,
                showBookmarks = false,
                addressBarText = url,
                tabs = it.tabs.map { tab -> if (tab.id == it.activeTabId) tab.copy(url = url) else tab },
            )
        }
    }

    // --- Downloads -------------------------------------------------------

    /**
     * Hands a download off to Android's own `DownloadManager` — the same
     * system-level download handling any real browser uses, with its own
     * progress notification and a file that ends up in the device's
     * normal Downloads folder, not something reinvented here.
     */
    fun onDownloadRequested(request: DownloadRequest) {
        val app = getApplication<Application>()
        val fileName = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        val downloadManager = app.getSystemService(DownloadManager::class.java) ?: return
        val dmRequest = runCatching {
            DownloadManager.Request(Uri.parse(request.url)).apply {
                request.mimeType?.let { setMimeType(it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
            }
        }.getOrNull() ?: return
        val id = runCatching { downloadManager.enqueue(dmRequest) }.getOrNull() ?: return
        _uiState.update { it.copy(lastDownloadStarted = fileName) }
        viewModelScope.launch {
            browserDataStore.recordDownload(DownloadRecord(id, request.url, fileName, System.currentTimeMillis()))
        }
    }

    fun dismissDownloadStartedMessage() = _uiState.update { it.copy(lastDownloadStarted = null) }

    // --- Scripts (page automation) --------------------------------------

    fun setShowScripts(show: Boolean) = _uiState.update { it.copy(showScripts = show) }

    fun startNewScript() = _uiState.update { it.copy(editingScript = BrowserScript(id = BrowserScriptStore.newId())) }

    fun startEditScript(script: BrowserScript) = _uiState.update { it.copy(editingScript = script) }

    fun dismissScriptEditor() = _uiState.update { it.copy(editingScript = null) }

    fun saveScript(script: BrowserScript) {
        viewModelScope.launch { scriptStore.addOrUpdate(script) }
        _uiState.update { it.copy(editingScript = null) }
    }

    fun deleteScript(id: String) {
        viewModelScope.launch { scriptStore.remove(id) }
        _uiState.update { it.copy(editingScript = null) }
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { scriptStore.setEnabled(id, enabled) }
    }

    /** Enabled scripts whose match pattern hits [url] — what [BrowserScreen] runs once a page finishes loading. */
    fun scriptsForUrl(url: String): List<BrowserScript> = _uiState.value.scripts.filter { it.enabled && it.matchesUrl(url) }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(application) as T
    }
}
