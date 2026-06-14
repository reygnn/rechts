package com.github.reygnn.rechts.ui.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.Parcel
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

/**
 * Grace period after which the throwaway onCreateWindow capture WebView is torn
 * down if it never captured a navigation (e.g. a `window.open()` with no URL).
 * Without this it would leak — it only self-destructs from inside
 * `shouldOverrideUrlLoading`, which such a popup never reaches.
 */
private const val CAPTURE_WEBVIEW_TIMEOUT_MS = 10_000L

/**
 * Cap on the serialized WebView history we persist across process death. The
 * Binder transaction that carries saved instance state is bounded (~1 MB shared
 * across the whole activity), so keep our slice well under it; if a deep history
 * exceeds this we drop it rather than risk TransactionTooLargeException.
 */
private const val MAX_HISTORY_STATE_BYTES = 512 * 1024

/**
 * The [Activity] hosting this Compose tree, unwrapping any [ContextWrapper]
 * layers. `LocalContext` is normally the activity itself, but unwrap defensively
 * so a wrapped context (themed overlay, etc.) still resolves.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Size of [bundle] once marshalled, used to keep the persisted history bounded. */
private fun marshalledSize(bundle: Bundle): Int {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(bundle)
        parcel.dataSize()
    } finally {
        parcel.recycle()
    }
}

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

    // Private mode is only real when the platform WebView supports isolated
    // profiles. Without that, binding "private" browsing to the default profile
    // would leak it into persistent normal browsing while still showing the lock
    // icon — a false privacy promise. So treat private mode as unavailable: seed
    // it off and disable the toggle (below) rather than silently faking it.
    val privateAvailable = remember { WebViewConfig.multiProfileSupported() }
    var isPrivate by rememberSaveable {
        mutableStateOf(settings.privateMode && privateAvailable)
    }

    // The initializer above gates on privateAvailable, but rememberSaveable
    // restores the *saved* value verbatim on a process-death restore without
    // re-running it. If the platform lost multi-profile support since that state
    // was written, drop back to normal mode — otherwise the lock icon would sit
    // over the (non-isolated) default profile, the exact false privacy promise
    // the gating avoids. Practically unreachable (WebView feature support doesn't
    // regress), but it keeps the "lock never lies" invariant total.
    LaunchedEffect(Unit) { if (isPrivate && !privateAvailable) isPrivate = false }

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

    // Throwaway capture WebViews spawned by onCreateWindow (popup handling).
    // Tracked here so they're torn down together with the main WebView (mode
    // switch / crash rebuild / leaving the screen) instead of lingering — and
    // holding the Activity context — until their self-destruct timeout fires.
    // Screen-scoped, not keyed on the WebView: only one main view is ever live,
    // and its captures are cleared in the DisposableEffect(webView) below.
    val captureWebViews = remember { mutableSetOf<WebView>() }

    // One WebView per mode (and per renderer-crash epoch): switching modes
    // recreates it bound to the other profile (normal = persistent default,
    // private = isolated, session-only).
    val webView = remember(isPrivate, webViewEpoch) {
        createBrowserWebView(context, viewModel, isPrivate, captureWebViews) {
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
            // Returning null means "nothing to persist": rememberSaveable then
            // restores to null and FirstLoadRouter sees no saved history (→
            // homepage). The alternative — an empty Bundle — would masquerade
            // as real history (hasSavedHistory == true) only to fail
            // restoreState one layer later, so prefer an honest null here.
            override fun SaverScope.save(value: Bundle?): Bundle? {
                // Skip serializing a private session entirely — its URLs must
                // never touch disk-persisted instance state.
                if (isPrivate) return null
                val bundle = Bundle()
                webView.saveState(bundle)
                // Drop private or oversize history (Binder budget) per the pure,
                // JVM-tested policy rather than reasoning about it inline.
                return bundle.takeIf {
                    HistoryPersistencePolicy.shouldPersist(
                        isPrivate = isPrivate,
                        marshalledSize = marshalledSize(it),
                        maxBytes = MAX_HISTORY_STATE_BYTES,
                    )
                }
            }

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
            val action = FirstLoadRouter.decide(
                crashReload = crashReload,
                inbound = inbound,
                firstComposition = firstComposition,
                isPrivate = isPrivate,
                hasSavedHistory = savedHistory != null,
            )
            when (action) {
                is FirstLoadAction.LoadInWebView -> webView.loadUrl(action.url)

                // Hand the inbound link off to another app. (data:/javascript:/app
                // deep links arrive here, never into the WebView.) This path is
                // reached only on a cold launch carrying such a link, so on a
                // successful handoff there is nothing of our own to show —
                // finish rather than popping the homepage into the foreground.
                // Only on failure do we honour an intent: fallback page, else
                // the homepage.
                is FirstLoadAction.DispatchInbound ->
                    if (ExternalScheme.dispatch(context, action.url)) {
                        context.findActivity()?.finish()
                    } else {
                        webView.loadUrl(ExternalScheme.fallbackUrl(action.url) ?: UrlNormalizer.HOME_URL)
                    }

                // restoreState rebuilds the history synchronously; mirror the
                // back/forward list into the chrome now, before the restored
                // page's first callback. If it fails, fall back to the homepage.
                FirstLoadAction.RestoreHistory ->
                    if (savedHistory != null && webView.restoreState(savedHistory!!) != null) {
                        viewModel.onHistoryRestored(
                            webView.url,
                            webView.canGoBack(),
                            webView.canGoForward(),
                        )
                    } else {
                        webView.loadUrl(UrlNormalizer.HOME_URL)
                    }
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
                // No page load follows a handoff, so re-sync the address bar.
                ExternalScheme.dispatch(context, url) -> viewModel.onExternalHandoff()
                // No app handled it: honour an intent: fallback page if any,
                // otherwise tell the user instead of failing silently.
                else -> when (val fallback = ExternalScheme.fallbackUrl(url)) {
                    null -> {
                        Toast.makeText(context, R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
                        viewModel.onExternalHandoff()
                    }
                    else -> webView.loadUrl(fallback)
                }
            }
        }
    }

    // Whether the address bar currently holds focus. Mirrored locally (the
    // ViewModel keeps its own copy for page-event handling) so system back can
    // abandon an in-progress edit first.
    var addressFocused by remember { mutableStateOf(false) }

    // System back: while the address bar is being edited, first leave the edit
    // (clearFocus → the ViewModel restores the page URL) instead of navigating
    // page history or exiting the app; otherwise navigate back, then leave the
    // app. The IME normally swallows back to hide itself before this fires, so
    // this is the deterministic fallback once the keyboard is already down.
    BackHandler(enabled = addressFocused || state.canGoBack) {
        if (addressFocused) focusManager.clearFocus() else webView.goBack()
    }

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
            // Destroy any popup-capture WebViews this main view spawned that
            // haven't self-destructed yet, so they don't outlive it (holding the
            // Activity context) until their timeout. Membership in the set is the
            // single-owner token: whoever removes a WebView owns its destroy(),
            // so the post/timeout paths below skip one already cleared here.
            captureWebViews.forEach { it.destroy() }
            captureWebViews.clear()
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
                privateAvailable = privateAvailable,
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onReloadOrStop = {
                    if (state.isLoading) {
                        webView.stopLoading()
                        // stopLoading() doesn't reliably push a final
                        // onProgressChanged(100)/onPageFinished, so settle the
                        // chrome ourselves — otherwise the progress bar and the
                        // stop icon could stay stuck. onStopLoading() also
                        // suppresses any trailing progress tick for the stopped
                        // page so the bar can't flicker back in.
                        viewModel.onStopLoading(webView.canGoBack(), webView.canGoForward())
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
                onAddressFocusChanged = { focused ->
                    addressFocused = focused
                    viewModel.onAddressFocusChanged(focused)
                },
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
    captureWebViews: MutableSet<WebView>,
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

        // A page asked to open a new window (target="_blank" or window.open).
        // rechts has no tabs, so route the destination into THIS WebView rather
        // than dropping it — with multiple windows enabled (WebViewConfig) the
        // platform default would otherwise just no-op such links.
        //
        // onCreateWindow carries no URL, so a throwaway WebView captures the
        // popup's first navigation and hands it back to the main view through
        // the usual scheme gate (http(s) loads here; an external scheme is
        // dispatched). Only honour user-initiated opens: a gesture-less
        // window.open is popup spam and is blocked (returning false).
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val href = WebView(view.context)
            // Track for lifecycle teardown (see DisposableEffect(webView)).
            captureWebViews.add(href)
            // Same jar as the main view: if this capture WebView ever did load
            // something, it must not spill into the persistent default profile
            // during a private session. It stays otherwise locked down (no
            // WebViewConfig.apply → JavaScript off); its only job is the capture
            // below.
            WebViewConfig.bindProfile(href, privateMode)
            var captured = false
            href.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    inner: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    // window.open() / window.open('about:blank') navigates the
                    // popup to about:blank FIRST; that's not the real target.
                    // Redirecting it into the main view would blank the user's
                    // current page and discard the real URL the opener sets via
                    // w.location afterwards. Let the (locked-down) capture view
                    // load about:blank and keep waiting for the real navigation.
                    if (url == "about:blank") return false
                    captured = true
                    when {
                        ExternalScheme.isInternal(url) -> view.loadUrl(url)
                        ExternalScheme.dispatch(inner.context, url, fromWebContent = true) -> Unit
                        else -> ExternalScheme.fallbackUrl(url)?.let(view::loadUrl)
                    }
                    // The capture WebView has done its one job; tear it down off
                    // the callback stack (destroy() mid-callback is unsafe). The
                    // set-membership check is the single-owner guard so the screen
                    // tearing it down first can't cause a double destroy.
                    inner.post { if (captureWebViews.remove(href)) inner.destroy() }
                    return true
                }
            }
            transport.webView = href
            resultMsg.sendToTarget()
            // Safety net against a popup that never triggers a navigation we can
            // capture (e.g. window.open() with no URL): without this the capture
            // WebView would leak, since it only self-destructs from inside the
            // override above. No-op once it has done its job (or the screen tore
            // it down first — the membership check guards the double destroy).
            href.postDelayed(
                { if (!captured && captureWebViews.remove(href)) href.destroy() },
                CAPTURE_WEBVIEW_TIMEOUT_MS,
            )
            return true
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
            notifyUnsupported(context, R.string.file_upload_unsupported)
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
    privateAvailable: Boolean,
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
                    privateAvailable = privateAvailable,
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
    privateAvailable: Boolean,
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
                // Disabled when the platform WebView can't isolate profiles —
                // honest "unavailable" beats a lock icon over non-private browsing.
                enabled = privateAvailable,
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
