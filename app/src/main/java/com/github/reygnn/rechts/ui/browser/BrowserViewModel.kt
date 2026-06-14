package com.github.reygnn.rechts.ui.browser

import androidx.lifecycle.ViewModel
import com.github.reygnn.rechts.domain.UrlNormalizer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the observable browser-chrome state and turns address-bar input into
 * load requests. The [android.webkit.WebView] is driven by the composable; this
 * ViewModel never touches Android types, so it stays JVM-unit-testable.
 *
 * Navigation callbacks (page started/finished, progress) flow *in* from the
 * WebView via the `on…` methods; load requests flow *out* via [loadRequests],
 * which the composable collects and forwards to the WebView.
 */
class BrowserViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /**
     * URLs to load. A buffered [Channel] (not a `replay = 0` SharedFlow) so a
     * request submitted before the collector attaches is buffered and still
     * delivered, never silently dropped.
     */
    private val _loadRequests = Channel<String>(Channel.BUFFERED)
    val loadRequests: Flow<String> = _loadRequests.receiveAsFlow()

    /** True while the user is editing the address bar — page events then leave it alone. */
    private var addressBarFocused = false

    // ── User input ────────────────────────────────────────────────────────

    fun onAddressChange(text: String) {
        _uiState.update { it.copy(addressText = text) }
    }

    fun onAddressClear() {
        _uiState.update { it.copy(addressText = "") }
    }

    fun onAddressFocusChanged(focused: Boolean) {
        addressBarFocused = focused
        // Abandoning an edit (blur without submit) restores the real page URL.
        if (!focused) _uiState.update { it.copy(addressText = it.currentUrl) }
    }

    /** Normalize the address-bar text and request a load. No-op if blank. */
    fun onAddressSubmit() {
        val target = UrlNormalizer.toUrlOrSearch(_uiState.value.addressText) ?: return
        _loadRequests.trySend(target)
    }

    // ── WebView callbacks ─────────────────────────────────────────────────

    fun onPageStarted(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                addressText = if (addressBarFocused) it.addressText else url,
                progress = 0,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { it.copy(progress = progress) }
    }

    fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                addressText = if (addressBarFocused) it.addressText else url,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }
}
