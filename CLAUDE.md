# CLAUDE.md

Project conventions for **rechts** — a minimal, single-page Android browser.
The name nods to the old text browser *links* (German: *links* = left,
*rechts* = right).

Claude Code reads this file automatically at session start. Keep it short and
actionable — not a marketing description (that's `README.md`).

For the working agreement, see `WHY_CLAUDE.md`. For Material 3 rules, see
`MATERIAL3RULES.md`. For test conventions, see `app/src/test/CLAUDE.md` and
`app/src/test/java/com/github/reygnn/rechts/TESTING_CONVENTIONS.kt`.

Cross-project conventions (stack baseline, git workflow, test philosophy,
code language) live in `~/.claude/CLAUDE.md` and are loaded automatically.
This file only holds what is specific to this project.

---

## Scope

rechts is **one WebView and an address bar**. That's the whole product.

It does:
- load a URL or run a web search from a single address bar,
- back / forward / reload / stop,
- show load progress,
- open `http`/`https` links from other apps (can be set as the default
  browser; an overflow menu offers an in-app "set as default" prompt),
- offer an optional, profile-isolated private mode (overflow menu).

It deliberately does **not** have (each is a new feature category that needs
explicit user agreement before being built):
- tabs,
- bookmarks or history persistence,
- downloads, find-in-page, settings screen,
- a custom rendering engine — it wraps the platform `WebView`. That's the
  only realistic option and it's the whole point of "minimal".

---

## Stack particulars

Almost baseline. The browser is the platform `android.webkit.WebView`. Two
project-specific bits:
- **`androidx.webkit`** — its `Profile` API gives the optional private mode a
  cookie/storage jar that's isolated from normal browsing.
- **SharedPreferences** (one boolean: the remembered browsing mode) — DataStore
  would be overkill for a single value.

No Room, no DI framework, no custom `Application` class.

---

## Architecture

Single Gradle module (`:app`). The `WebView` owns the page; a tiny ViewModel
mirrors only the chrome state. No repository layer — there's no data to
persist.

```
com.github.reygnn.rechts/
  ui/
    MainActivity.kt              — ComponentActivity (singleTask), edge-to-edge, hosts BrowserScreen; wipes private profile on cold start; routes inbound VIEW intents (onCreate + onNewIntent)
    browser/
      BrowserScreen.kt           — Compose chrome + AndroidView(SwipeRefreshLayout→WebView, pull-to-refresh); overflow menu (back/forward/reload row, private toggle, set-default); wires WebViewClient/ChromeClient → VM
      BrowserViewModel.kt        — StateFlow<BrowserUiState> + buffered Channel<String> loadRequests (also openExternalUrl for inbound links)
      BrowserUiState.kt          — chrome state (address, progress, history flags)
    theme/                       — RechtsTheme, static teal Color palette
  domain/
    UrlNormalizer.kt             — pure: typed text → URL or DuckDuckGo search
  data/
    SettingsStore.kt             — SharedPreferences; remembers the browsing mode
  web/
    WebViewConfig.kt             — WebView settings + profile binding (normal vs private) + per-profile cookie policy + private wipe
    ExternalScheme.kt            — pure scheme check + Intent dispatch for non-http(s) links (mailto:, tel:, intent:, …)
```

Data flow: WebView callbacks (`onPageStarted`, `onProgressChanged`, …) push
*into* the ViewModel; the ViewModel emits load requests *out* via
`loadRequests`, which `BrowserScreen` collects and forwards to the WebView.

---

## Hard rules

1. **The WebView is the source of truth for the page.** `BrowserViewModel`
   only mirrors chrome state for rendering — never try to make it own
   navigation history or page content.
2. **`UrlNormalizer` stays pure** (no Android imports). It's the one piece of
   real logic and must remain JVM-unit-testable. New address-bar behaviour
   goes here, with a test.
3. **Privacy defaults are load-bearing, not incidental:** JavaScript on,
   first-party cookies on, **third-party cookies off**, no file/content
   access. Live in `WebViewConfig`. Don't loosen them without a reason.
   **Private mode is optional and profile-isolated:** normal browsing uses the
   default `ProfileStore` profile (persistent — saved logins live here, never
   wipe it); private browsing uses the separate `WebViewConfig.PRIVATE_PROFILE`,
   wiped in `MainActivity.onCreate` (`clearPrivateData()`). It runs
   unconditionally: rotation no longer reaches `onCreate` (`configChanges`), so
   the only calls are a genuine cold launch and an OS process-death restore —
   in both the private session must start fresh (see hard rule 5: private
   history is never persisted/restored either). The normal profile is untouched.
   Switching mode rebuilds the WebView on the other profile — that's why the
   WebView is `remember(isPrivate, webViewEpoch)` and the `AndroidView` sits in
   `key(webView)`.
   The chosen mode persists via `SettingsStore` (SharedPreferences).
4. **DuckDuckGo is the default** (privacy-friendly), but homepage and search
   are deliberately two endpoints: `UrlNormalizer.HOME_URL` =
   `start.duckduckgo.com` (tidier start page, used by `BrowserScreen`),
   `UrlNormalizer.SEARCH_PREFIX` = `duckduckgo.com/?q=` (canonical results
   page). Both live in `UrlNormalizer`.
5. **The Activity handles configuration changes itself** via
   `android:configChanges` (orientation, resize, dark mode, font scale,
   locale, …). That keeps the live WebView — and its open page, scroll
   position, history and form input — alive across rotation instead of
   recreating it and reloading the homepage. Compose still updates theme,
   layout direction, etc. through `LocalConfiguration`, so this is NOT an
   orientation lock. Don't drop `configChanges` without restoring WebView
   state some other way (`saveState`/`restoreState`), or rotation will reload
   the homepage again. **Process death** (not config change) *is* covered by
   `WebView.saveState`/`restoreState`: `BrowserScreen` snapshots the history
   into a `rememberSaveable` `Bundle` and restores it on the first composition
   only. **A mode switch** also rebuilds the WebView, and a switch *back to
   normal* restores the normal session — but from a **separate, in-memory**
   snapshot (`NormalHistoryHolder`), taken in `onTogglePrivate` when leaving
   normal and never written to disk. Switching *into* private always opens the
   homepage. Both restore sources flow through `FirstLoadRouter` (disk on the
   first composition, the in-memory normal snapshot on a later switch); the
   caller picks the bundle by `firstComposition`. **A private session is never
   persisted, in memory or on disk**: the disk saver skips `saveState` while
   `isPrivate`, `onTogglePrivate` only snapshots when leaving *normal*, and
   every restore branch is gated on `!isPrivate` — so private URLs never reach
   instance state and a private session never survives a mode switch or process
   death. A render-process crash (`onRenderProcessGone`) rebuilds the WebView
   via a `webViewEpoch` key instead of letting the platform kill the app
   process.
6. **Non-WebView schemes go to other apps:** the WebView only loads
   `http`/`https`/`about`. `web/ExternalScheme` intercepts everything else
   (`mailto:`, `tel:`, `intent:`, app deep links) — both in-page link taps
   (`shouldOverrideUrlLoading`) and typed input — and dispatches an
   `ACTION_VIEW` intent instead of letting the WebView error with
   `ERR_UNKNOWN_URL_SCHEME`. `intent:` URLs are hardened (component/selector
   cleared, `BROWSABLE` required) before launch.
7. **Default-browser eligibility is load-bearing:** the `VIEW` + `BROWSABLE` +
   `DEFAULT` (`http`/`https`) intent filter on `MainActivity` is what makes
   rechts selectable as a default browser — don't remove it. The activity is
   `singleTask`; inbound links arrive via `onCreate` (cold) and `onNewIntent`
   (running) and are routed to the WebView (`viewIntentUrl` →
   `BrowserViewModel.openExternalUrl`, which loads the URL *without*
   normalization). Both paths still pass through the `ExternalScheme.isInternal`
   gate before loading, so an explicit intent carrying a non-`http(s)` scheme
   (`data:`, `javascript:`, …) is handed off to another app, never loaded in the
   WebView. The in-app "set as default" action uses `RoleManager.ROLE_BROWSER`.
8. **A private session is never persisted or restored; switching into private
   starts fresh at the homepage.** Two distinct guarantees:
   - **Never persisted/restored (the privacy core).** A private session is never
     written to disk or to the in-memory normal snapshot, and never
     reconstructed from either. The disk history saver returns null while
     `isPrivate` (plus the `HistoryPersistencePolicy` check); the normal snapshot
     is taken in `onTogglePrivate` only when *leaving normal* (never private);
     and both `FirstLoadRouter` restore branches are gated on `!isPrivate`.
   - **Toggling into private opens the homepage.** A mode switch into private has
     no inbound URL (consumed) and no crash reload, so `FirstLoadRouter` falls
     through to `UrlNormalizer.HOME_URL` — a clean slate, the user-visible half
     of the privacy promise in hard rule 3.

   This is **not** "private ignores every navigation and forces the homepage."
   Private is still a working browser: typed input and in-page navigation load
   normally, an **inbound `VIEW` link** opens its URL in private (hard rule 7 —
   the inbound branch precedes the restore branches and is intentionally not
   `isPrivate`-gated), and a **renderer-crash recovery** reopens the current
   private page from in-memory `reloadAfterCrash` (live-session recovery, never
   persisted). None of these reconstruct a *previous* session, so none violate
   the privacy core. Don't add a "remember the private page across launches /
   mode switches" path; equally, don't force inbound links or crash recovery to
   the homepage.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list.
- Not the place for transient refactor notes — those belong in commit
  messages on short-lived feature branches.
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
