# rechts

A minimal Android browser. One WebView, one address bar — nothing else.

The name is a wink at [**links**](https://en.wikipedia.org/wiki/Links_(web_browser)),
the venerable text-mode browser: *links* is German for "left", so the
companion is *rechts* — "right".

## What it does

- Type a URL or a search term in the single address bar; rechts figures out
  which you meant and either loads the page or searches (DuckDuckGo). A bare
  host is opened over `https` — including `localhost`, so a local dev server on
  plain `http` needs the `http://` prefix typed explicitly.
- Back, forward, reload / stop.
- A slim load-progress bar.
- Opens `http`/`https` links from other apps — can be set as your default
  browser (there's an in-app "set as default" entry in the ⋮ menu).
- Save the current page to a small favorites list, then reopen, reorder
  (drag-and-drop) or delete entries on a dedicated favorites screen (⋮ menu).
- Hands file downloads to Android's system download manager — it fetches to
  your public `Downloads/` folder with its own notification; there's no in-app
  download list. It does ask first before re-downloading a file whose name
  you've saved before.
- Picks a file for a web upload form (`<input type="file">`) through the
  system file picker.
- Plays HTML5 `<video>` fullscreen, rotating to landscape to fill the screen
  (like Chrome); back or the page's own control exits.
- Optional private mode (⋮ menu).

That's the whole feature set, on purpose. No tabs, no history, no settings
screen — the favorites list is the one deliberate, minimal exception to
"no saved browsing state".

## Privacy

JavaScript is on (the modern web needs it), **third-party cookies are blocked**,
web content can't reach the device's file system, app data is excluded from
cloud backup, and search defaults to DuckDuckGo.

One deliberate exception: a file download — even in private mode — is handed to
Android's system download manager and saved to the public `Downloads/` folder,
the same as any browser.

**Optional private mode.** Tap the lock icon to toggle it. Normal browsing uses a
persistent profile, so logins and cookies are remembered across launches.
Private browsing uses a separate, isolated profile: its cookies and storage
never touch the normal ones and are wiped on the next cold launch, so a private
session doesn't outlive the app. The chosen mode is remembered.

Isolation uses the platform WebView's multi-profile support; on the unlikely
device without it, private mode falls back to the normal profile.

## Build & run

```bash
./gradlew assembleRelease      # unsigned release APK/AAB
./gradlew testDebugUnitTest    # JVM unit tests
```

Requirements: Android 16 (API 36) device/emulator, JDK 21. Signing and
on-device install happen via the shared family tooling (`~/apk/install-aab.sh`),
not in Gradle — see `~/.claude/CLAUDE.md`.

## Tech

Kotlin · Jetpack Compose · Material 3 · `android.webkit.WebView`. Single
Gradle module, no DI framework, minimal persistence (DataStore for the
favorites list, SharedPreferences for the browsing mode — no Room). See
`CLAUDE.md` for the architecture and hard rules.
