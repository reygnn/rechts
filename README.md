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

That's the whole feature set, on purpose. No tabs, no bookmarks, no history,
no settings.

## Privacy

Out of the box: JavaScript on (the modern web needs it), first-party cookies
allowed, **third-party cookies blocked**, and web content can't reach the
device's file system. Search defaults to DuckDuckGo.

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
