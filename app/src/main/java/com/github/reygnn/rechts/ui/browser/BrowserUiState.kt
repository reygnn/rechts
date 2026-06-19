package com.github.reygnn.rechts.ui.browser

/**
 * Everything the browser chrome needs to render. The [android.webkit.WebView]
 * itself owns the page; this state only mirrors what the chrome shows.
 *
 * @param addressText   current text in the address bar (user-editable).
 * @param currentUrl    URL of the page actually loaded; the address bar
 *                      re-syncs to this when the user isn't editing.
 * @param pageTitle     title of the loaded page (from `onReceivedTitle`); used
 *                      as the display name when saving a favorite. Blank until
 *                      the page reports one; the URL is the fallback.
 * @param progress      load progress 0–100; `100` means idle/done.
 * @param canGoBack     whether the WebView has back history.
 * @param canGoForward  whether the WebView has forward history.
 */
data class BrowserUiState(
    val addressText: String = "",
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
) {
    /** Derived from [progress] — no separate field to keep in sync. */
    val isLoading: Boolean get() = progress < 100
}
