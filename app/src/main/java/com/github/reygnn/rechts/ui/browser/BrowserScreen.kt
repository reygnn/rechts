package com.github.reygnn.rechts.ui.browser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.reygnn.rechts.R
import com.github.reygnn.rechts.data.SettingsStore
import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.web.ExternalScheme
import com.github.reygnn.rechts.web.WebViewConfig

private const val TAG = "BrowserScreen"

/** Minimum spacing between repeated "unsupported" toasts (download / file upload). */
private const val UNSUPPORTED_TOAST_MIN_INTERVAL_MS = 3_000L

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val layoutDirection = LocalLayoutDirection.current

    // Chosen browsing mode, seeded from the persisted preference and remembered
    // across configuration changes. Toggling it persists the choice and (via
    // remember(isPrivate) below) rebuilds the WebView on the right profile.
    val settings = remember { SettingsStore(context) }
    var isPrivate by rememberSaveable { mutableStateOf(settings.privateMode) }

    // Bumped when the render process dies (see onRenderProcessGone); keying the
    // WebView on it forces a fresh instance instead of letting the platform kill
    // the whole app process. Not saveable — a renderer crash isn't a state to
    // restore across process death.
    var webViewEpoch by remember { mutableIntStateOf(0) }

    // URL to reopen after a renderer-crash rebuild (captured before the bump).
    var reloadAfterCrash by remember { mutableStateOf<String?>(null) }

    // Breaks a renderer crash loop: a single crash recovers transparently, but a
    // page that crashes on every load would otherwise spin a tight rebuild →
    // crash → rebuild loop. Survives the WebView rebuilds (remember, not keyed).
    val crashReloadGate = remember { CrashReloadGate() }

    // One WebView per mode (and per renderer-crash epoch): switching modes
    // recreates it bound to the other profile (normal = persistent default,
    // private = isolated, session-only).
    val webView = remember(isPrivate, webViewEpoch) {
        createBrowserWebView(context, viewModel, isPrivate) {
            // Reopen the page only while we're not in a crash loop; once the
            // gate trips, drop the crashing URL and let the first-load effect
            // fall back to the homepage with a notice.
            reloadAfterCrash = if (crashReloadGate.shouldReload(SystemClock.uptimeMillis())) {
                viewModel.uiState.value.currentUrl.ifBlank { null }
            } else {
                Log.w(TAG, "Renderer crash loop detected; falling back to homepage")
                Toast.makeText(context, R.string.page_crash_repeated, Toast.LENGTH_LONG).show()
                null
            }
            webViewEpoch++
        }
    }

    // Serialized back/forward history, persisted ONLY across process death.
    // android:configChanges keeps the live WebView across rotation, so the
    // saver below never runs then; it snapshots the current WebView only when
    // the OS persists UI state to kill and later recreate the process.
    val savedHistory by rememberSaveable(
        stateSaver = object : Saver<Bundle?, Bundle> {
            override fun SaverScope.save(value: Bundle?): Bundle =
                // Never persist a private session: writing its URLs into the
                // on-disk instance state would let private browsing outlive the
                // app process. Normal browsing snapshots its history as usual.
                Bundle().also { if (!isPrivate) webView.saveState(it) }

            override fun restore(value: Bundle): Bundle? = value
        },
    ) { mutableStateOf<Bundle?>(null) }

    // True only on the very first composition (including after process death),
    // so a mode switch — which also rebuilds the WebView — never restores the
    // other profile's old history.
    var firstComposition by remember { mutableStateOf(true) }

    // First load for this WebView: an inbound link if we were launched from
    // one, else restored history (process-death only), else the homepage.
    // pendingInitialUrl is consumed once so later WebViews (mode switch) don't
    // reopen it.
    var pendingInitialUrl by rememberSaveable { mutableStateOf(initialUrl) }
    LaunchedEffect(webView) {
        if (webView.url == null) {
            val crashReload = reloadAfterCrash
            reloadAfterCrash = null
            val inbound = pendingInitialUrl
            pendingInitialUrl = null
            when {
                // Renderer was rebuilt after a crash: reopen the page we were on.
                crashReload != null -> webView.loadUrl(crashReload)

                // Inbound link from another app. Route it through the same scheme
                // gate as every other load — only http(s)/about go into the
                // WebView; anything else (an explicit intent could carry data:,
                // javascript:, …) is handed off to another app, not loaded here.
                inbound != null ->
                    if (ExternalScheme.isInternal(inbound)) {
                        webView.loadUrl(inbound)
                    } else {
                        val handled = ExternalScheme.dispatch(context, inbound)
                        val fallback = if (handled) null else ExternalScheme.fallbackUrl(inbound)
                        webView.loadUrl(fallback ?: UrlNormalizer.HOME_URL)
                    }

                // Process-death restore — normal mode only. A private session is
                // never reconstructed from disk-persisted state (the saver above
                // also refuses to write it).
                firstComposition && !isPrivate && savedHistory != null &&
                    webView.restoreState(savedHistory!!) != null ->
                    // History restored: mirror the back/forward list into the
                    // chrome now, before the restored page's first callback.
                    viewModel.onHistoryRestored(
                        webView.url,
                        webView.canGoBack(),
                        webView.canGoForward(),
                    )

                else -> webView.loadUrl(UrlNormalizer.HOME_URL)
            }
            firstComposition = false
        }
    }

    // Forward load requests out of the ViewModel: http(s)/about into the
    // WebView, everything else (mailto:, tel:, …) off to another app.
    LaunchedEffect(webView) {
        viewModel.loadRequests.collect { url ->
            when {
                ExternalScheme.isInternal(url) -> webView.loadUrl(url)
                // Typed input is an explicit user action, so no gesture gate.
                ExternalScheme.dispatch(context, url) -> Unit
                // No app handled it: honour an intent: fallback page if any,
                // otherwise tell the user instead of failing silently.
                else -> when (val fallback = ExternalScheme.fallbackUrl(url)) {
                    null -> Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
                    else -> webView.loadUrl(fallback)
                }
            }
        }
    }

    // System back navigates page history before leaving the app.
    BackHandler(enabled = state.canGoBack) { webView.goBack() }

    // Suspend the WebView with the app: AndroidView gives no lifecycle bridge,
    // so without this a backgrounded page keeps running JS timers and media.
    // pauseTimers() is what actually stops JS (onPause() alone does not); it is
    // process-global, which is fine here as only one WebView is ever active.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(webView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { webView.onResume(); webView.resumeTimers() }
                Lifecycle.Event.ON_PAUSE -> { webView.onPause(); webView.pauseTimers() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Destroy the WebView when it's replaced (mode switch) or the screen leaves.
    // Detach it from its parent first: destroy() on a still-attached WebView is
    // the documented gotcha (logs a warning / can leak), so remove it explicitly
    // rather than relying on the AndroidView teardown order.
    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* result not needed — the system applies the choice */ }

    val onSetDefaultBrowser: () -> Unit = {
        val roleManager = context.getSystemService(RoleManager::class.java)
        when {
            roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) ->
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

            roleManager.isRoleHeld(RoleManager.ROLE_BROWSER) ->
                Toast.makeText(context, R.string.already_default, Toast.LENGTH_SHORT).show()

            else ->
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(Modifier.fillMaxSize()) {
            BrowserChrome(
                state = state,
                isPrivate = isPrivate,
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onReloadOrStop = {
                    if (state.isLoading) {
                        webView.stopLoading()
                        // stopLoading() doesn't reliably push a final
                        // onProgressChanged(100)/onPageFinished, so settle the
                        // chrome ourselves — otherwise the progress bar and the
                        // stop icon could stay stuck.
                        viewModel.onProgressChanged(100)
                    } else {
                        webView.reload()
                    }
                },
                onTogglePrivate = {
                    val next = !isPrivate
                    isPrivate = next
                    settings.privateMode = next
                },
                onSetDefaultBrowser = onSetDefaultBrowser,
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
                    // Bottom (navigation-bar) and side insets belong here; the
                    // chrome already consumes the top + side insets itself. The
                    // sides matter in landscape (gesture nav / display cutout).
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                )
            }
        }
    }
}

private fun createBrowserWebView(
    context: Context,
    viewModel: BrowserViewModel,
    privateMode: Boolean,
    onRenderGone: () -> Unit,
): WebView {
    // Cheap throttle shared by the download + file-upload notices so a hostile
    // page that auto-fires either in a loop can't flood the screen with toasts.
    val noticeGate = MinIntervalGate(UNSUPPORTED_TOAST_MIN_INTERVAL_MS)
    fun notifyUnsupported(ctx: Context, messageRes: Int) {
        if (!noticeGate.accept(SystemClock.uptimeMillis())) return
        Toast.makeText(ctx, messageRes, Toast.LENGTH_SHORT).show()
    }

    return WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    webViewClient = object : WebViewClient() {
        // Let other apps handle non-http(s) links (mailto:, tel:, intent:, …);
        // the WebView itself can't load them and would error out.
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            return when (ExternalScheme.decideWebContentNavigation(url, request.hasGesture())) {
                ExternalScheme.WebNavigation.LoadInWebView -> false

                // Gesture-less external navigation: swallow it and stay put.
                ExternalScheme.WebNavigation.Ignore -> {
                    Log.d(TAG, "Ignoring gesture-less external navigation: $url")
                    true
                }

                // Hand off to another app; if none handled it, fall back to the
                // page the intent: author named.
                ExternalScheme.WebNavigation.DispatchExternal -> {
                    if (!ExternalScheme.dispatch(view.context, url, fromWebContent = true)) {
                        ExternalScheme.fallbackUrl(url)?.let(view::loadUrl)
                    }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            viewModel.onPageStarted(url, view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(view: WebView, url: String) {
            viewModel.onPageFinished(url, view.canGoBack(), view.canGoForward())
        }

        // The render process died (OOM, or a crash on a heavy/hostile page).
        // Returning the default would tear down the whole app process; instead
        // report it so the caller drops this now-defunct WebView and rebuilds a
        // fresh one (reopening the page we were on).
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            Log.w(TAG, "WebView render process gone (didCrash=${detail.didCrash()})")
            onRenderGone()
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            viewModel.onProgressChanged(newProgress)
        }

        // rechts requests no device capabilities for web content. Deny
        // geolocation and camera/mic/etc. deterministically — the platform
        // default leaves the JS request unanswered (it hangs) rather than
        // cleanly rejecting it.
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        // File upload is out of scope (CLAUDE.md). The default would silently
        // do nothing; cancel the request cleanly and tell the user instead.
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            notifyUnsupported(webView.context, R.string.file_upload_unsupported)
            return true
        }
    }

    // Downloads are out of scope (CLAUDE.md). Don't fail silently on a download
    // link — let the user know nothing will happen.
    setDownloadListener { _, _, _, _, _ ->
        notifyUnsupported(context, R.string.download_unsupported)
    }

    // Bind the profile (private vs default) BEFORE the WebView loads anything.
    WebViewConfig.apply(this, privateMode)
    }
}

@Composable
private fun BrowserChrome(
    state: BrowserUiState,
    isPrivate: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onTogglePrivate: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    onAddressChange: (String) -> Unit,
    onAddressClear: () -> Unit,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressSubmit: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        // Top inset for the status bar plus the side insets (landscape gesture
        // nav / display cutout); the Surface background still spans edge-to-edge.
        Column(
            Modifier.windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AddressField(
                    value = state.addressText,
                    isPrivate = isPrivate,
                    onValueChange = onAddressChange,
                    onClear = onAddressClear,
                    onFocusChanged = onAddressFocusChanged,
                    onSubmit = onAddressSubmit,
                    modifier = Modifier.weight(1f),
                )
                // Back / forward / reload-stop live inside the overflow menu now.
                OverflowMenu(
                    state = state,
                    isPrivate = isPrivate,
                    onBack = onBack,
                    onForward = onForward,
                    onReloadOrStop = onReloadOrStop,
                    onTogglePrivate = onTogglePrivate,
                    onSetDefaultBrowser = onSetDefaultBrowser,
                )
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
private fun OverflowMenu(
    state: BrowserUiState,
    isPrivate: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    onTogglePrivate: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Back / forward / reload-stop, arranged horizontally at the top.
            // Each closes the menu and acts (navigation leaves the menu anyway).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = { expanded = false; onBack() },
                    enabled = state.canGoBack,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                }
                IconButton(
                    onClick = { expanded = false; onForward() },
                    enabled = state.canGoForward,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.nav_forward))
                }
                IconButton(onClick = { expanded = false; onReloadOrStop() }) {
                    if (state.isLoading) {
                        Icon(Icons.Filled.Close, stringResource(R.string.nav_stop))
                    } else {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.nav_reload))
                    }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_private_mode)) },
                onClick = {
                    expanded = false
                    onTogglePrivate()
                },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = {
                    if (isPrivate) Icon(Icons.Filled.Check, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_set_default)) },
                onClick = {
                    expanded = false
                    onSetDefaultBrowser()
                },
            )
        }
    }
}

@Composable
private fun AddressField(
    value: String,
    isPrivate: Boolean,
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
        leadingIcon = {
            // The leading icon doubles as the private-mode indicator.
            if (isPrivate) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.private_active),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.address_clear))
                }
            }
        },
        // Plain text keyboard, not KeyboardType.Uri: the bar doubles as a search
        // box, and many IMEs drop the space bar in URI mode. Auto-capitalization
        // and autocorrect are off so typed URLs aren't mangled.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
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
