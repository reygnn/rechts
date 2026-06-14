package com.github.reygnn.rechts.ui.browser

import android.app.Activity
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
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
import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.web.WebViewConfig

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current

    // The WebView is created once and survives recomposition. It is the source
    // of truth for the page; the ViewModel only mirrors chrome state.
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            WebViewConfig.apply(this)

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
        }
    }

    // Load the homepage on first composition only.
    LaunchedEffect(Unit) {
        if (webView.url == null) webView.loadUrl(UrlNormalizer.HOME_URL)
    }

    // Forward load requests coming out of the ViewModel to the WebView.
    LaunchedEffect(webView) {
        viewModel.loadRequests.collect { url -> webView.loadUrl(url) }
    }

    // System back navigates page history before leaving the app.
    BackHandler(enabled = state.canGoBack) { webView.goBack() }

    DisposableEffect(Unit) {
        onDispose {
            // Private by default: when the user is actually leaving (not a
            // configuration change), wipe cookies, web storage, cache and
            // history so nothing from this session survives.
            if (activity?.isFinishing == true) {
                WebViewConfig.clearAll(webView)
            }
            webView.destroy()
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(Modifier.fillMaxSize()) {
            BrowserChrome(
                state = state,
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onReloadOrStop = {
                    if (state.isLoading) webView.stopLoading() else webView.reload()
                },
                onAddressChange = viewModel::onAddressChange,
                onAddressClear = viewModel::onAddressClear,
                onAddressFocusChanged = viewModel::onAddressFocusChanged,
                onAddressSubmit = {
                    viewModel.onAddressSubmit()
                    focusManager.clearFocus()
                },
            )
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

@Composable
private fun BrowserChrome(
    state: BrowserUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
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
