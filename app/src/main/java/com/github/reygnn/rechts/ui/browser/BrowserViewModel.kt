package com.github.reygnn.rechts.ui.browser

import androidx.lifecycle.ViewModel
import com.github.reygnn.rechts.domain.UrlNormalizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the observable browser-chrome state and turns address-bar input into
 * load requests. The [android.webkit.WebView] is driven by the composable; this
 * ViewModel never touches Android types, so it stays JVM-unit-testable.
 *
 * Navigation callbacks (page started/finished, progress, history) flow *in*
 * from the WebView via the `on…` methods; load requests flow *out* via
 * [loadRequests], which the composable collects and forwards to the WebView.
 */
class BrowserViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /**
     * One-shot URLs to load. `replay = 0` with a small drop-oldest buffer so an
     * emission never suspends even if the collector is momentarily absent.
     */
    private val _loadRequests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val loadRequests: SharedFlow<String> = _loadRequests.asSharedFlow()

    // ── User input ────────────────────────────────────────────────────────

    fun onAddressChange(text: String) {
        _uiState.update { it.copy(addressText = text) }
    }

    fun onAddressClear() {
        _uiState.update { it.copy(addressText = "") }
    }

    /** Normalize the address-bar text and request a load. No-op if blank. */
    fun onAddressSubmit() {
        val target = UrlNormalizer.toUrlOrSearch(_uiState.value.addressText) ?: return
        _loadRequests.tryEmit(target)
    }

    // ── WebView callbacks ─────────────────────────────────────────────────

    fun onPageStarted(url: String) {
        _uiState.update { it.copy(currentUrl = url, addressText = url, isLoading = true, progress = 0) }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update { it.copy(progress = progress, isLoading = progress < 100) }
    }

    fun onPageFinished(url: String) {
        _uiState.update { it.copy(currentUrl = url, addressText = url, isLoading = false, progress = 100) }
    }

    fun onTitleChanged(title: String) {
        _uiState.update { it.copy(pageTitle = title) }
    }

    fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }
}
