package com.evan.lazlo.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evan.lazlo.browser.BrowserEngineLoader
import com.evan.lazlo.browser.EngineKind
import com.evan.lazlo.browser.GeckoModuleState
import com.evan.lazlo.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

private const val START_PAGE = "https://duckduckgo.com/"

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    val browserEngineLoader = BrowserEngineLoader(application)

    private val _uiState = MutableStateFlow(BrowserUiState(addressBarText = START_PAGE))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val kind = settings.browserEngine()
            // GeckoView was picked in a previous session but its module
            // isn't installed this time (a fresh install, cleared data,
            // or the OS uninstalled an unused split) — fall back to
            // Chromium rather than silently failing to render anything.
            val usable = if (kind == EngineKind.GECKO && !browserEngineLoader.isGeckoModuleInstalled()) {
                EngineKind.CHROMIUM
            } else {
                kind
            }
            _uiState.update { it.copy(engineKind = usable) }
        }
    }

    /** The page a freshly-(re)created engine instance should load — the last known URL, or a sensible start page. */
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
            launch {
                browserEngineLoader.installState().collect { state ->
                    _uiState.update { it.copy(geckoModuleState = state) }
                    if (state is GeckoModuleState.Installed) applyEngine(EngineKind.GECKO)
                }
            }
            browserEngineLoader.requestInstall()
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
        _uiState.update {
            it.copy(currentUrl = url, addressBarText = url, canGoBack = canGoBack, canGoForward = canGoForward)
        }
    }

    fun onLoadingChanged(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(application) as T
    }
}
