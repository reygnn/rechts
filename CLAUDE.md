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
- show load progress.

It deliberately does **not** have (each is a new feature category that needs
explicit user agreement before being built):
- tabs,
- bookmarks or history persistence,
- downloads, find-in-page, settings screen,
- a custom rendering engine — it wraps the platform `WebView`. That's the
  only realistic option and it's the whole point of "minimal".

---

## Stack particulars

Baseline only — nothing project-specific. No extra dependencies beyond the
shared core: the browser is built on the platform `android.webkit.WebView`,
which needs no library. No DataStore, no Room, no DI framework, no custom
`Application` class.

---

## Architecture

Single Gradle module (`:app`). The `WebView` owns the page; a tiny ViewModel
mirrors only the chrome state. No repository layer — there's no data to
persist.

```
com.github.reygnn.rechts/
  ui/
    MainActivity.kt              — ComponentActivity, edge-to-edge, hosts BrowserScreen
    browser/
      BrowserScreen.kt           — Compose chrome + AndroidView(WebView); wires WebViewClient/ChromeClient → VM
      BrowserViewModel.kt        — StateFlow<BrowserUiState> + buffered Channel<String> loadRequests
      BrowserUiState.kt          — chrome state (address, progress, history flags)
    theme/                       — RechtsTheme, static teal Color palette
  domain/
    UrlNormalizer.kt             — pure: typed text → URL or DuckDuckGo search
  web/
    WebViewConfig.kt             — applies the "JS on, private" WebView settings
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
   **Private by default:** the session is wiped (cookies, web storage, cache,
   history) on a cold launch (`MainActivity.onCreate`, guarded by
   `savedInstanceState == null`) and on real exit (`BrowserScreen`'s
   `onDispose` when `activity.isFinishing`). A config-change recreation must
   NOT wipe — that's why both checks exist. Wiping lives in
   `WebViewConfig.clearAll` / `clearCookiesAndStorage`.
4. **DuckDuckGo is the search + home default** (privacy-friendly). Single
   source of truth: `UrlNormalizer.HOME_URL`; the search endpoint
   (`SEARCH_PREFIX`) is derived from it and `BrowserScreen` reads
   `UrlNormalizer.HOME_URL` for the homepage. Change it in one place.
5. **Known limitation:** WebView page state is lost on configuration change
   (rotation) — acceptable for "minimal". Don't paper over it with an
   orientation lock; if it ever matters, solve it with proper state saving.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list.
- Not the place for transient refactor notes — those belong in commit
  messages on short-lived feature branches.
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).
