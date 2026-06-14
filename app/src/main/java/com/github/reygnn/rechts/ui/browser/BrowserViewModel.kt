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

    /**
     * Set when a submit just fired a load. The blur that follows
     * [onAddressSubmit] (the UI clears focus) must then NOT restore the old
     * page URL, or the previous URL would flash for a frame before the new
     * page's [onPageStarted] arrives. Consumed by the next [onAddressFocusChanged].
     */
    private var submitInFlight = false

    /**
     * Set when the user stops a load. `stopLoading()` is asynchronous, so the
     * engine can still emit a trailing `onProgressChanged(<100)` (or a late
     * sub-resource tick) for the page being stopped, which would re-show the
     * progress bar after we settled it. Suppress those until the next genuine
     * navigation ([onPageStarted]) clears the flag.
     */
    private var loadingStopped = false

    // ── User input ────────────────────────────────────────────────────────

    fun onAddressChange(text: String) {
        _uiState.update { it.copy(addressText = text) }
    }

    fun onAddressClear() {
        _uiState.update { it.copy(addressText = "") }
    }

    fun onAddressFocusChanged(focused: Boolean) {
        addressBarFocused = focused
        if (focused) {
            // Gaining focus closes any prior submit's blur window: clear a stale
            // flag so a later abandon-edit blur still restores the page URL even
            // if the post-submit blur never arrived (e.g. focus was already
            // elsewhere when submit fired).
            submitInFlight = false
        } else {
            // A blur right after a submit must leave the entry alone — the
            // imminent page load updates the bar. Otherwise (blur abandoning an
            // edit) restore the real page URL.
            if (submitInFlight) submitInFlight = false
            else _uiState.update { it.copy(addressText = it.currentUrl) }
        }
    }

    /** Normalize the address-bar text and request a load. No-op if blank. */
    fun onAddressSubmit() {
        val target = UrlNormalizer.toUrlOrSearch(_uiState.value.addressText) ?: return
        submitInFlight = true
        // Best-effort: the BUFFERED channel has ample capacity, and a request
        // sent before the collector attaches is buffered (not dropped).
        _loadRequests.trySend(target)
    }

    /**
     * The user stopped the in-flight load. Settle the chrome to idle now —
     * `WebView.stopLoading()` doesn't reliably push a final
     * `onProgressChanged(100)`/`onPageFinished` — and ignore any trailing
     * progress ticks for the stopped page until the next [onPageStarted].
     *
     * Mirror the WebView's current [canGoBack]/[canGoForward] too: stopping can
     * interrupt a navigation that already committed, so the back/forward state
     * may have changed and would otherwise stay stale until the next callback.
     */
    fun onStopLoading(canGoBack: Boolean, canGoForward: Boolean) {
        loadingStopped = true
        _uiState.update {
            it.copy(progress = 100, canGoBack = canGoBack, canGoForward = canGoForward)
        }
    }

    /**
     * Request a load of an already-formed URL coming from outside (an inbound
     * ACTION_VIEW intent — e.g. a link tapped in another app). No normalization:
     * the URL is taken as-is.
     */
    fun openExternalUrl(url: String) {
        _loadRequests.trySend(url)
    }

    /**
     * A typed address-bar entry was handed off to another app (an external
     * scheme like `mailto:`/`tel:`) instead of loading a page. No page load
     * follows, so no `onPageStarted` will re-sync the bar — do it here, or the
     * dispatched URI would linger in the address bar over the unchanged page.
     * Leaves the entry alone if the user is editing it.
     */
    fun onExternalHandoff() {
        if (addressBarFocused) return
        _uiState.update { it.copy(addressText = it.currentUrl) }
    }

    /**
     * Mirror a back/forward list brought back by `WebView.restoreState` (process
     * death only). restoreState rebuilds the history synchronously, so push its
     * flags into the chrome now — otherwise [canGoBack] stays `false` until the
     * restored page's first callback and the system Back button would leave the
     * app instead of going back.
     */
    fun onHistoryRestored(url: String?, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url ?: it.currentUrl,
                addressText = url ?: it.addressText,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    // ── WebView callbacks ─────────────────────────────────────────────────

    fun onPageStarted(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        // A genuine new navigation — progress is meaningful again.
        loadingStopped = false
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
        // A trailing tick for a load the user just stopped must not re-show the
        // progress bar; ignore until the next navigation resets the flag.
        if (loadingStopped) return
        _uiState.update { it.copy(progress = progress) }
    }

    fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                // After a user-initiated stop, a trailing onPageFinished for the
                // stopped page must not move the chrome to its partially-loaded
                // URL — keep it as onStopLoading() settled it (mirrors the
                // onProgressChanged guard). The back/forward flags are still worth
                // mirroring: the navigation may already have committed.
                currentUrl = if (loadingStopped) it.currentUrl else url,
                addressText = if (loadingStopped || addressBarFocused) it.addressText else url,
                progress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }
}
