# rechts

A minimal Android browser. One WebView, one address bar — nothing else.

The name is a wink at [**links**](https://en.wikipedia.org/wiki/Links_(web_browser)),
the venerable text-mode browser: *links* is German for "left", so the
companion is *rechts* — "right".

## What it does

- Type a URL or a search term in the single address bar; rechts figures out
  which you meant and either loads the page or searches (DuckDuckGo).
- Back, forward, reload / stop.
- A slim load-progress bar.
- Opens `http`/`https` links from other apps — can be set as your default
  browser (there's an in-app "set as default" entry in the ⋮ menu).
- Optional private mode (⋮ menu).

That's the whole feature set, on purpose. No tabs, no bookmarks, no history.

## Privacy

JavaScript is on (the modern web needs it), **third-party cookies are blocked**,
web content can't reach the device's file system, app data is excluded from
cloud backup, and search defaults to DuckDuckGo.

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
Gradle module, no DI framework, no persistence. See `CLAUDE.md` for the
architecture and hard rules.
