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
      BrowserScreen.kt           — Compose chrome + AndroidView(WebView); overflow menu (private toggle, set-default); wires WebViewClient/ChromeClient → VM
      BrowserViewModel.kt        — StateFlow<BrowserUiState> + buffered Channel<String> loadRequests (also openExternalUrl for inbound links)
      BrowserUiState.kt          — chrome state (address, progress, history flags)
    theme/                       — RechtsTheme, static teal Color palette
  domain/
    UrlNormalizer.kt             — pure: typed text → URL or DuckDuckGo search
  data/
    SettingsStore.kt             — SharedPreferences; remembers the browsing mode
  web/
    WebViewConfig.kt             — WebView settings + profile binding (normal vs private) + private wipe
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
   wiped on a cold launch (`MainActivity.onCreate` → `clearPrivateData()`,
   guarded by `savedInstanceState == null` so rotation keeps the session).
   Switching mode rebuilds the WebView on the other profile — that's why the
   WebView is `remember(isPrivate)` and the `AndroidView` sits in `key(webView)`.
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
   the homepage again.
6. **Default-browser eligibility is load-bearing:** the `VIEW` + `BROWSABLE` +
   `DEFAULT` (`http`/`https`) intent filter on `MainActivity` is what makes
   rechts selectable as a default browser — don't remove it. The activity is
   `singleTask`; inbound links arrive via `onCreate` (cold) and `onNewIntent`
   (running) and are routed to the WebView (`viewIntentUrl` →
   `BrowserViewModel.openExternalUrl`, which loads the URL *without*
   normalization). The in-app "set as default" action uses
   `RoleManager.ROLE_BROWSER`.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list.
- Not the place for transient refactor notes — those belong in commit
  messages on short-lived feature branches.
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
