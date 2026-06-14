package com.github.reygnn.rechts.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.rechts.R
import com.github.reygnn.rechts.data.SettingsStore
import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.web.WebViewConfig

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Chosen browsing mode, seeded from the persisted preference and remembered
    // across configuration changes. Toggling it persists the choice and (via
    // remember(isPrivate) below) rebuilds the WebView on the right profile.
    val settings = remember { SettingsStore(context) }
    var isPrivate by rememberSaveable { mutableStateOf(settings.privateMode) }

    // One WebView per mode: switching modes recreates it bound to the other
    // profile (normal = persistent default, private = isolated, session-only).
    val webView = remember(isPrivate) { createBrowserWebView(context, viewModel, isPrivate) }

    // Load the homepage whenever a fresh WebView appears (launch or mode switch).
    LaunchedEffect(webView) {
        if (webView.url == null) webView.loadUrl(UrlNormalizer.HOME_URL)
    }

    // Forward load requests coming out of the ViewModel to the current WebView.
    LaunchedEffect(webView) {
        viewModel.loadRequests.collect { url -> webView.loadUrl(url) }
    }

    // System back navigates page history before leaving the app.
    BackHandler(enabled = state.canGoBack) { webView.goBack() }

    // Destroy the WebView when it's replaced (mode switch) or the screen leaves.
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(Modifier.fillMaxSize()) {
            BrowserChrome(
                state = state,
                isPrivate = isPrivate,
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onReloadOrStop = {
                    if (state.isLoading) webView.stopLoading() else webView.reload()
                },
                onTogglePrivate = {
                    val next = !isPrivate
                    isPrivate = next
                    settings.privateMode = next
                },
                onAddressChange = viewModel::onAddressChange,
                onAddressClear = viewModel::onAddressClear,
                onAddressFocusChanged = viewModel::onAddressFocusChanged,
                onAddressSubmit = {
                    viewModel.onAddressSubmit()
                    focusManager.clearFocus()
                },
            )
            // key(webView) so AndroidView swaps to the new instance on a mode switch.
            key(webView) {
                AndroidView(
                    factory = { webView },
                    // Only the bottom (navigation-bar) inset belongs here; the
                    // chrome already consumes the top inset via statusBarsPadding().
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = contentPadding.calculateBottomPadding()),
                )
            }
        }
    }
}

private fun createBrowserWebView(
    context: Context,
    viewModel: BrowserViewModel,
    privateMode: Boolean,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            viewModel.onPageStarted(url, view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(view: WebView, url: String) {
            viewModel.onPageFinished(url, view.canGoBack(), view.canGoForward())
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            viewModel.onProgressChanged(newProgress)
        }
    }

    // Bind the profile (private vs default) BEFORE the WebView loads anything.
    WebViewConfig.apply(this, privateMode)
}

@Composable
private fun BrowserChrome(
    state: BrowserUiState,
    isPrivate: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onTogglePrivate: () -> Unit,
    onAddressChange: (String) -> Unit,
    onAddressClear: () -> Unit,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressSubmit: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                }
                IconButton(onClick = onForward, enabled = state.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.nav_forward))
                }
                AddressField(
                    value = state.addressText,
                    onValueChange = onAddressChange,
                    onClear = onAddressClear,
                    onFocusChanged = onAddressFocusChanged,
                    onSubmit = onAddressSubmit,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onReloadOrStop) {
                    if (state.isLoading) {
                        Icon(Icons.Filled.Close, stringResource(R.string.nav_stop))
                    } else {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.nav_reload))
                    }
                }
                // Private-mode toggle: highlighted (primary) when active.
                IconButton(
                    onClick = onTogglePrivate,
                    colors = if (isPrivate) {
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(
                            if (isPrivate) R.string.private_disable else R.string.private_enable,
                        ),
                    )
                }
            }

            // Determinate progress bar. Always laid out (only faded out when
            // idle) so showing/hiding it never shifts the WebView below. When
            // idle it is also removed from the semantics tree, so a screen
            // reader doesn't announce an invisible "100%" progress bar.
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (state.isLoading) 1f else 0f)
                    .then(if (state.isLoading) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
    }
}

@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { onFocusChanged(it.isFocused) },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.address_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.address_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
