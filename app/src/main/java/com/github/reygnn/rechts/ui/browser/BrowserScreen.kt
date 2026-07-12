package com.github.reygnn.rechts.ui.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.Parcel
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.reygnn.rechts.R
import com.github.reygnn.rechts.data.FavoritesStore
import com.github.reygnn.rechts.data.SettingsStore
import com.github.reygnn.rechts.domain.FavoritesOps
import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.ui.favorites.FavoritesScreen
import com.github.reygnn.rechts.ui.theme.privateTintFor
import com.github.reygnn.rechts.web.DownloadHandler
import com.github.reygnn.rechts.web.ExternalScheme
import com.github.reygnn.rechts.web.PopupCaptureClient
import com.github.reygnn.rechts.web.WebViewConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BrowserScreen"

/** Minimum spacing between repeated download notices (started/unsupported/failed). */
private const val NOTICE_TOAST_MIN_INTERVAL_MS = 3_000L

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

/**
 * True only when rechts currently holds the default-browser role. Used to hide
 * the "set as default" menu entry when it would be a no-op. Returns false when
 * the role is unavailable (unusual device) — the entry then stays visible and
 * routes to system settings, rather than silently vanishing.
 */
private fun Context.isDefaultBrowser(): Boolean {
    val roleManager = getSystemService(RoleManager::class.java) ?: return false
    return roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
        roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
}

/**
 * Non-snapshot holder for the normal session's serialized history, kept IN
 * MEMORY across a mode switch only. Switching into private mode rebuilds (and
 * destroys) the normal WebView; this lets a later switch back to normal restore
 * the page + back/forward stack. Deliberately NOT [rememberSaveable]: it must
 * never reach disk (process death uses the separate, size-capped history saver),
 * and a private session is never captured here. A plain holder rather than a
 * Compose state — it's only read/written from callbacks, never during layout.
 */
private class NormalHistoryHolder {
    var bundle: Bundle? = null
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

    // Favorites: one shared, persistent list (DataStore JSON blob). Collected
    // here so the overflow star reflects whether the current page is saved. The
    // favorites screen is shown as an overlay on top of the browser (not a
    // screen swap), so the live WebView stays composed underneath.
    val favoritesStore = remember { FavoritesStore(context) }
    val favorites by favoritesStore.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoritesScope = rememberCoroutineScope()
    // Not rememberSaveable on purpose: after process death we start in the
    // browser over the restoring WebView, not reopened on top of it.
    var showFavorites by remember { mutableStateOf(false) }

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

    // In-memory snapshot of the normal session, taken when the user switches into
    // private mode and restored when they switch back (see onTogglePrivate + the
    // first-load effect). Never written to disk; private is never captured.
    val normalHistory = remember { NormalHistoryHolder() }

    // File upload (<input type="file">) plumbing. onShowFileChooser hands us a
    // one-shot callback that must be answered exactly once — with the picked
    // URIs or null on cancel — so we park it here until the system picker
    // returns. The picker runs as a separate activity; results come back through
    // the launcher below. Privacy stays intact: the picker yields content:// URIs
    // the WebView reads via a temporary grant, so WebViewConfig's allowFileAccess
    // (file:// access) can remain off.
    //
    // Plain remember, not rememberSaveable: a ValueCallback can't be serialized,
    // and the render process owning it dies with the process anyway. The trade-off
    // is that if the OS kills us while the picker is foreground, the parked
    // callback is gone on restore and the launcher's redelivered result lands on a
    // null pendingFileChooser → silently dropped (the form's input stays empty, as
    // if cancelled). Rare, no error, user just re-taps; not worth persisting an
    // unsaveable callback for.
    val pendingFileChooser = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    fun deliverFileChooser(uris: Array<Uri>?) {
        val callback = pendingFileChooser.value
        pendingFileChooser.value = null
        // The callback belongs to the render process; if its WebView was torn
        // down meanwhile (mode switch / crash) delivering may throw — swallow it.
        runCatching { callback?.onReceiveValue(uris) }
            .onFailure { Log.w(TAG, "File-chooser callback delivery failed", it) }
    }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // parseResult turns the picker's result into the URI array the WebView
        // expects, handling single pick, multi-pick (clipData) and cancel (null).
        deliverFileChooser(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }
    // Launch the platform-built chooser intent for an <input type="file"> request.
    // The intent already honours the input's accept types and multiple flag.
    val launchFilePicker: (ValueCallback<Array<Uri>>, Intent) -> Unit = { callback, intent ->
        // A fresh chooser before the previous one returned: answer the stale
        // callback (null) so its page isn't left waiting, then take over.
        if (pendingFileChooser.value != null) deliverFileChooser(null)
        pendingFileChooser.value = callback
        try {
            fileChooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // No app can serve the picker — answer the page and tell the user.
            Log.w(TAG, "No activity available to pick a file", e)
            deliverFileChooser(null)
            Toast.makeText(context, R.string.file_upload_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    // Download flow. The WebView's download listener hands a request here; we
    // check rechts' own DownloadManager history off the main thread and either
    // enqueue it straight away or park it for a confirmation dialog when a file
    // of the same name was downloaded before. pendingDownload is plain remember,
    // like pendingFileChooser: a prompt needn't survive process death — a lost
    // one just means no download happened and the user re-triggers it.
    val downloadScope = rememberCoroutineScope()
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    // Throttle the download notices so a page auto-firing downloads in a loop
    // can't flood the screen with toasts (moved here from the WebView factory so
    // the enqueue and its notice live together).
    val downloadNoticeGate = remember { MinIntervalGate(NOTICE_TOAST_MIN_INTERVAL_MS) }
    fun enqueueDownload(request: DownloadRequest) {
        val noticeRes = when (
            DownloadHandler.enqueue(
                context,
                request.url,
                request.userAgent,
                request.contentDisposition,
                request.mimeType,
                request.cookie,
            )
        ) {
            DownloadHandler.Outcome.STARTED -> R.string.download_started
            DownloadHandler.Outcome.UNSUPPORTED -> R.string.download_unsupported
            DownloadHandler.Outcome.FAILED -> R.string.download_failed
        }
        if (downloadNoticeGate.accept(SystemClock.uptimeMillis())) {
            Toast.makeText(context, noticeRes, Toast.LENGTH_SHORT).show()
        }
    }
    val requestDownload: (DownloadRequest) -> Unit = { request ->
        // Only a fetchable http(s) download can already be in the history; a
        // blob:/data: one goes straight through to its "unsupported" notice.
        if (!DownloadHandler.isEnqueueable(request.url)) {
            enqueueDownload(request)
        } else {
            downloadScope.launch {
                val alreadyDownloaded = withContext(Dispatchers.IO) {
                    DownloadHandler.isDuplicate(
                        DownloadHandler.existingDownloadNames(context),
                        request.fileName,
                    )
                }
                when {
                    // Not a known file: enqueue straight away.
                    !alreadyDownloaded -> enqueueDownload(request)
                    // Known file, no prompt open yet: ask before re-downloading.
                    pendingDownload == null -> pendingDownload = request
                    // Known file, but a prompt is already open for an earlier
                    // one — only one dialog at a time. Enqueue this rather than
                    // overwriting (and silently dropping) the pending request;
                    // resuming on the single-threaded main dispatcher keeps the
                    // check-then-set atomic, so two duplicates can't both park.
                    else -> enqueueDownload(request)
                }
            }
        }
    }

    // HTML5 fullscreen video: the WebChromeClient hands the platform-built
    // fullscreen view to this controller, which hosts it over the whole activity
    // (decorView) and hides the system bars. Tied to the Activity's window;
    // null only if there's somehow no Activity (then fullscreen is refused
    // cleanly). isFullscreen mirrors the state so system back exits fullscreen
    // instead of navigating page history (see BackHandler below).
    val activity = remember(context) { context.findActivity() }
    var isFullscreen by remember { mutableStateOf(false) }
    val fullscreenController = remember(activity) {
        activity?.let { FullscreenVideoController(it) { showing -> isFullscreen = showing } }
    }

    // One WebView per mode (and per renderer-crash epoch): switching modes
    // recreates it bound to the other profile (normal = persistent default,
    // private = isolated, session-only).
    val webView = remember(isPrivate, webViewEpoch) {
        createBrowserWebView(
            context, viewModel, isPrivate, captureWebViews, launchFilePicker, fullscreenController,
            requestDownload,
        ) {
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

    // True only on the very first composition (including after process death).
    // It distinguishes the two restore sources: disk history on the first
    // composition, the in-memory normal snapshot on a later mode switch. A
    // private session is never restored from either.
    var firstComposition by remember { mutableStateOf(true) }

    // First load for this WebView: an inbound link if we were launched from one,
    // else restored history (disk on first composition, the in-memory normal
    // snapshot when switching back from private), else the homepage.
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
                hasNormalModeHistory = normalHistory.bundle != null,
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
                // page's first callback. The source is the disk bundle on the
                // first composition (process death), else the in-memory normal
                // snapshot (switching back from private). If it fails, fall back
                // to the homepage.
                FirstLoadAction.RestoreHistory -> {
                    val restoreBundle = if (firstComposition) savedHistory else normalHistory.bundle
                    // Consume the in-memory snapshot so it can't restore twice.
                    if (!firstComposition) normalHistory.bundle = null
                    if (restoreBundle != null && webView.restoreState(restoreBundle) != null) {
                        viewModel.onHistoryRestored(
                            webView.url,
                            webView.canGoBack(),
                            webView.canGoForward(),
                        )
                    } else {
                        webView.loadUrl(UrlNormalizer.HOME_URL)
                    }
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

    // While a video is fullscreen, back leaves fullscreen rather than navigating
    // page history or exiting. Registered after the handler above so it wins
    // (Compose consults back handlers in reverse registration order).
    BackHandler(enabled = isFullscreen) { fullscreenController?.hide() }

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
            // If this WebView is replaced (mode switch / crash rebuild) or the
            // screen leaves while a video is fullscreen, drop the custom view and
            // restore the system bars — otherwise they'd stay hidden with no way
            // back. Safe no-op when nothing is fullscreen.
            fullscreenController?.hide()
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

    // Whether rechts is the default browser, so the "set as default" menu entry
    // can hide once it's a no-op. Re-checked on every resume: the user can change
    // the default from the role dialog or system settings and come back, and
    // resume covers both return paths.
    var isDefaultBrowser by remember { mutableStateOf(context.isDefaultBrowser()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefaultBrowser = context.isDefaultBrowser()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Share the current page URL out to another app, or copy it to the clipboard.
    // Both no-op on a blank URL (the menu entries are disabled then), so a launch
    // screen with no page can't share an empty string.
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val onSharePage: () -> Unit = {
        val url = state.currentUrl
        if (url.isNotBlank()) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                // Give share targets (email, messaging) a readable subject. Read
                // the title off the live WebView — the freshest value.
                webView.title?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            context.startActivity(Intent.createChooser(send, null))
        }
    }
    val onCopyLink: () -> Unit = {
        val url = state.currentUrl
        if (url.isNotBlank()) {
            // The clip label shows in the system clipboard UI even when the
            // content is hidden, so keep the URL out of it. In private mode also
            // flag the clip sensitive so the URL stays out of the clipboard
            // preview/history. Android 13+ shows its own "copied" toast.
            val clip = ClipData.newPlainText(context.getString(R.string.copied_link_label), url)
            if (isPrivate) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard?.setPrimaryClip(clip)
        }
    }

    // Pull-to-refresh host wrapping the WebView. One per WebView (mode switch /
    // crash rebuild make a new one), so it always wraps the live instance. The
    // gesture only fires at the top of the page — SwipeRefreshLayout consults the
    // WebView's own canScrollVertically(-1) — and reload() keeps the WebView the
    // source of truth for the page (hard rule 1).
    val spinnerColor = MaterialTheme.colorScheme.primary.toArgb()
    val swipeRefresh = remember(webView) {
        SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(spinnerColor)
            setOnRefreshListener { webView.reload() }
            addView(webView)
        }
    }

    // Stop the spinner once the (re)load settles. The inline progress bar already
    // shows ongoing progress, so the spinner only acknowledges the gesture until
    // the page finishes; keyed on isLoading so it clears on the load's end, not
    // on the gesture itself (which sets isRefreshing while isLoading is still
    // false from the previous idle page).
    LaunchedEffect(swipeRefresh, state.isLoading) {
        if (!state.isLoading) swipeRefresh.isRefreshing = false
    }

    // Current page is favoritable only once it has a real URL. The list scan is
    // memoized so it doesn't re-run on every progress-tick recomposition.
    val canFavorite = state.currentUrl.isNotBlank()
    val isCurrentFavorite = remember(favorites, state.currentUrl) {
        canFavorite && FavoritesOps.isFavorite(favorites, state.currentUrl)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
            Column(Modifier.fillMaxSize()) {
                BrowserChrome(
                    state = state,
                    isPrivate = isPrivate,
                    privateAvailable = privateAvailable,
                    isCurrentFavorite = isCurrentFavorite,
                    canFavorite = canFavorite,
                    onToggleFavorite = {
                        // Read the title straight off the live WebView at save
                        // time — that's always the freshest value, with no
                        // mirrored-state window where favoriting saves the URL
                        // as the name. Fall back to the URL when it's blank.
                        val title = webView.title?.takeIf { it.isNotBlank() }
                            ?: state.currentUrl
                        favoritesScope.launch {
                            favoritesStore.toggle(state.currentUrl, title)
                        }
                    },
                    onOpenFavorites = { showFavorites = true },
                    onSharePage = onSharePage,
                    onCopyLink = onCopyLink,
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
                    showSetDefault = !isDefaultBrowser,
                    onTogglePrivate = {
                        val next = !isPrivate
                        // Leaving normal mode: snapshot its history (in memory) on the
                        // still-live WebView before it's rebuilt, so switching back
                        // restores the page + back/forward stack. A private session is
                        // never captured — it always reopens the homepage.
                        if (!isPrivate) {
                            val bundle = Bundle()
                            normalHistory.bundle = bundle.takeIf { webView.saveState(it) != null }
                        }
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
                // key(webView) so AndroidView swaps to the new instance on a mode
                // switch. The pull-to-refresh host is the view tree's root here; the
                // WebView is its single child.
                key(webView) {
                    AndroidView(
                        factory = { swipeRefresh },
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

        // Favorites overlay: drawn on top of the (still-composed) browser, so
        // the WebView keeps its page alive while the list is open. Opening a
        // favorite closes the overlay and loads the URL as-is (no normalization,
        // like an inbound link) in whichever mode is active.
        if (showFavorites) {
            FavoritesScreen(
                favorites = favorites,
                onOpenUrl = { url ->
                    showFavorites = false
                    viewModel.openExternalUrl(url)
                },
                onRemove = { url -> favoritesScope.launch { favoritesStore.remove(url) } },
                onReorder = { ordered -> favoritesScope.launch { favoritesStore.saveOrder(ordered) } },
                onClose = { showFavorites = false },
            )
        }

        // Confirm before re-downloading a file rechts already has: requestDownload
        // parked it here after finding a same-named entry in the download history.
        pendingDownload?.let { request ->
            AlertDialog(
                onDismissRequest = { pendingDownload = null },
                title = { Text(stringResource(R.string.download_exists_title)) },
                text = { Text(stringResource(R.string.download_exists_message, request.fileName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDownload = null
                            enqueueDownload(request)
                        },
                    ) { Text(stringResource(R.string.download_exists_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDownload = null }) {
                        Text(stringResource(R.string.download_exists_dismiss))
                    }
                },
            )
        }
    }
}

/**
 * A download the WebView signalled, captured from `setDownloadListener` with the
 * page session already resolved: the raw request params, the cookie of the
 * profile the download came from (the isolated jar in private mode), and the
 * file name it will be saved as. Snapshotting cookie + name here means a
 * confirmed re-download uses the session of the WebView that started it, not
 * whatever mode happens to be active when the user answers the dialog.
 */
private data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val cookie: String?,
    val fileName: String,
)

private fun createBrowserWebView(
    context: Context,
    viewModel: BrowserViewModel,
    privateMode: Boolean,
    captureWebViews: MutableSet<WebView>,
    launchFilePicker: (ValueCallback<Array<Uri>>, Intent) -> Unit,
    fullscreen: FullscreenVideoController?,
    requestDownload: (DownloadRequest) -> Unit,
    onRenderGone: () -> Unit,
): WebView {
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
            // Safety net: a page that navigates itself while a <video> is
            // fullscreen doesn't reliably fire onHideCustomView across every
            // navigation type, which would otherwise strand the custom view over
            // the new page with the chrome covered. hide() is a no-op when nothing
            // is fullscreen, so this is cheap on the common path.
            fullscreen?.hide()
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
            // Belt-and-suspenders profile binding: the shouldInterceptRequest net
            // below already blocks any real network load, but keep the capture
            // view on the same (private) jar as the main view so nothing could
            // spill into the persistent default profile even if that net is ever
            // weakened. It stays otherwise locked down (no WebViewConfig.apply →
            // JavaScript off); its only job is the capture below.
            WebViewConfig.bindProfile(href, privateMode)
            // Capture/blocking logic lives in the testable PopupCaptureClient;
            // here we only wire its side effects to this concrete WebView.
            href.webViewClient = PopupCaptureClient(
                // Route a captured popup target into the MAIN view (or off to
                // another app). Invoked on the main thread.
                routeTarget = { url ->
                    when {
                        ExternalScheme.isInternal(url) -> view.loadUrl(url)
                        ExternalScheme.dispatch(view.context, url, fromWebContent = true) -> Unit
                        else -> ExternalScheme.fallbackUrl(url)?.let(view::loadUrl)
                    }
                },
                // Single-owner teardown: set membership is the token, so whoever
                // removes the view owns its destroy() — no double free.
                tearDown = { if (captureWebViews.remove(href)) href.destroy() },
                postToMainThread = { href.post(it) },
            )
            transport.webView = href
            resultMsg.sendToTarget()
            // Safety net against a popup that never triggers a navigation we can
            // capture (e.g. window.open() with no URL): without this the capture
            // WebView would leak, since it otherwise self-destructs only from the
            // capture paths above. The set-membership check is the single-owner
            // guard, so this no-ops once the view has been torn down elsewhere
            // (captured + removed, or the screen disposed first).
            href.postDelayed(
                { if (captureWebViews.remove(href)) href.destroy() },
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

        // An <input type="file"> was activated. Hand the platform-built chooser
        // intent (it already carries the input's accept types + multiple flag) to
        // the composable, which launches it and answers filePathCallback with the
        // picked URIs (or null on cancel) once the picker returns. Returning true
        // takes ownership of the one-shot callback.
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            launchFilePicker(filePathCallback, fileChooserParams.createIntent())
            return true
        }

        // A page element (typically a <video>) entered HTML5 fullscreen. The
        // platform hands us a ready-made view to display over the whole app;
        // FullscreenVideoController hosts it edge-to-edge and hides the system
        // bars. Without an Activity to host it we can't go fullscreen, so answer
        // the callback immediately — that drops the page back out of fullscreen
        // instead of leaving its video stuck in a fullscreen state we ignored.
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreen == null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreen.show(view, callback)
            // Match the screen orientation to the video's shape, like Chrome:
            // probe the video's intrinsic size (async, main thread) and lock
            // landscape for a wide clip, portrait for a tall one. lockOrientation
            // no-ops if fullscreen was already left by the time the result lands.
            this@apply.evaluateJavascript(VIDEO_DIMENSIONS_JS) { result ->
                fullscreen.lockOrientation(isPortraitVideo(result))
            }
        }

        // The page left fullscreen (its own control, or JS exiting the
        // Fullscreen API). Tear the custom view down and restore the chrome.
        override fun onHideCustomView() {
            fullscreen?.hide()
        }
    }

    // A download (Content-Disposition: attachment, or a MIME type the WebView
    // can't render) is handed to the system DownloadManager, which fetches it to
    // the public Downloads folder with its own progress/"done" notification. The
    // page's cookies + user-agent ride along so a logged-in download works
    // (private mode reads the isolated profile's jar). The outcome picks the
    // notice: a blob:/data: download has no fetchable URL (unsupported), a valid
    // http(s) download the platform refused gets a distinct "failed" notice, and
    // neither fails silently.
    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        // Snapshot the page session (the isolated profile's jar in private mode)
        // and the file name now, then hand the request up: the composable checks
        // rechts' own download history and either enqueues it straight away or
        // asks before re-downloading a file of the same name.
        val cookie = WebViewConfig.cookieFor(url, privateMode)
        val fileName = DownloadHandler.guessFileName(url, contentDisposition, mimeType)
        requestDownload(
            DownloadRequest(url, userAgent, contentDisposition, mimeType, cookie, fileName),
        )
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
    isCurrentFavorite: Boolean,
    canFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSharePage: () -> Unit,
    onCopyLink: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    showSetDefault: Boolean,
    onTogglePrivate: () -> Unit,
    onSetDefaultBrowser: () -> Unit,
    onAddressChange: (String) -> Unit,
    onAddressClear: () -> Unit,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressSubmit: () -> Unit,
) {
    // Top-chrome background — edge-to-edge, so it also paints behind the status
    // bar. Both modes blend their hue onto the same base bar at the SAME weight,
    // so normal (teal) and private (warm purple) read at identical brightness —
    // only the hue differs, the private one being a privacy signal alongside the
    // lock icon (which stays teal). The private tint is the teal rotated +125°
    // with S/L preserved, so equal blend weights => equal lightness. The light/dark
    // purple comes from `privateTintFor`, picked the way surfaceTint itself flips.
    val baseBar = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    // 0.18 is deliberately stronger than the bare ~8% elevation overlay, which was
    // too faint to read. Safe to push this hard because the status-bar icons below
    // now track the resulting bar luminance instead of the system theme.
    val barTint = if (isPrivate) {
        privateTintFor(MaterialTheme.colorScheme)
    } else {
        MaterialTheme.colorScheme.surfaceTint
    }
    val chromeColor = lerp(baseBar, barTint, 0.18f)

    // The system status bar sits on top of [chromeColor] (edge-to-edge). Drive its
    // icon (clock / battery) light-vs-dark appearance off the ACTUAL bar luminance,
    // not the system day/night theme: otherwise a strongly tinted bar could end up
    // with same-brightness, low-contrast icons. This decouples icon legibility from
    // how bold the chrome tint is — the load-bearing half of the private-bar signal.
    val view = LocalView.current
    // True when the bar is light, which asks the system for DARK icons (and vice versa).
    val lightStatusBar = chromeColor.luminance() > 0.5f
    SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                lightStatusBar
        }
    }

    Surface(color = chromeColor) {
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
                    isCurrentFavorite = isCurrentFavorite,
                    canFavorite = canFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onOpenFavorites = onOpenFavorites,
                    onSharePage = onSharePage,
                    onCopyLink = onCopyLink,
                    onBack = onBack,
                    onForward = onForward,
                    onReloadOrStop = onReloadOrStop,
                    showSetDefault = showSetDefault,
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
    isCurrentFavorite: Boolean,
    canFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSharePage: () -> Unit,
    onCopyLink: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReloadOrStop: () -> Unit,
    showSetDefault: Boolean,
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
            // Star the current page (filled = already saved). Disabled until the
            // page has a real URL.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isCurrentFavorite) R.string.menu_remove_favorite
                            else R.string.menu_add_favorite,
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    onToggleFavorite()
                },
                enabled = canFavorite,
                leadingIcon = {
                    Icon(
                        painter = painterResource(
                            if (isCurrentFavorite) R.drawable.ic_star else R.drawable.ic_star_border,
                        ),
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_favorites)) },
                onClick = {
                    expanded = false
                    onOpenFavorites()
                },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_star), contentDescription = null)
                },
            )
            // Share the current page URL out, or copy it. Both need a real URL.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share)) },
                onClick = {
                    expanded = false
                    onSharePage()
                },
                enabled = state.currentUrl.isNotBlank(),
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_copy_link)) },
                onClick = {
                    expanded = false
                    onCopyLink()
                },
                enabled = state.currentUrl.isNotBlank(),
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null)
                },
            )
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
            // Only offered while rechts isn't already the default — otherwise the
            // action would be a no-op. Tracked reactively (re-checked on resume).
            if (showSetDefault) {
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
        // KeyboardType.Uri (not plain Text): flags this as a URL field so URI-aware
        // IMEs suppress auto-capitalizing the TLD after a dot ("google.com", not
        // "google.Com"). The old worry that URI mode drops the space bar doesn't hold
        // for the IMEs we target — nah keeps the full space bar in URI fields, Gboard
        // only narrows it and adds "/"/".com" — and the bar still doubles as a search
        // box: a query with spaces stays enterable, only the IME signals "URL".
        // Auto-capitalization and autocorrect are off so typed URLs aren't mangled.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
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
