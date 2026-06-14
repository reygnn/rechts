package com.github.reygnn.rechts.ui.browser

/**
 * Everything the browser chrome needs to render. The [android.webkit.WebView]
 * itself owns the page; this state only mirrors what the chrome shows.
 *
 * @param addressText   current text in the address bar (user-editable).
 * @param currentUrl    URL of the page actually loaded.
 * @param pageTitle     title reported by the page (for accessibility / future use).
 * @param progress      load progress 0–100.
 * @param isLoading     true while a page is loading (progress < 100).
 * @param canGoBack     whether the WebView has back history.
 * @param canGoForward  whether the WebView has forward history.
 */
data class BrowserUiState(
    val addressText: String = "",
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)
