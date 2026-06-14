# CLAUDE.md (test source set)

Orientation for sessions touching the unit tests. Not a technical manual
— that's `java/com/github/reygnn/rechts/TESTING_CONVENTIONS.kt`, which holds
the concrete patterns. This file answers the _meta_ questions: what exists,
what is deliberately left out, where to start.

The baseline stack (JUnit 4, MockK, Turbine, kotlinx-coroutines-test) and
philosophy (JVM-only by default, pure logic outside Android-runtime classes,
MockK only — no Mockito) come from `~/.claude/CLAUDE.md`. This file only
records the project's choices on top of that baseline.

---

## What exists

```
domain/
  UrlNormalizerTest.kt          — pure address-bar logic: URL vs search, encoding, edge cases
ui/browser/
  BrowserViewModelTest.kt       — Turbine for state transitions + loadRequests SharedFlow
util/
  MainDispatcherRule.kt         — sets Dispatchers.Main = UnconfinedTestDispatcher
```

All JVM-only. Run with `./gradlew testDebugUnitTest`.

---

## What this set deliberately does NOT have

- **No tests for `BrowserScreen` / `WebView` wiring.** A `WebView` only runs
  on a real Android runtime; testing it would need Robolectric (or
  instrumented tests) and would mostly assert on mocked-out framework
  machinery. The testable logic was pushed *out* of the WebView into
  `UrlNormalizer` and `BrowserViewModel` — test those instead. (See the
  global "pure logic lives outside Android-runtime classes" rule.)
- **No Robolectric.** Nothing here needs Android runtime: `UrlNormalizer`
  uses `java.net.URLEncoder` (pure JVM), the ViewModel touches no Android
  types.
- **No androidTest source set.** No instrumented behaviour worth pinning.

---

## When you come into this project

### Reading order

1. **First** `java/com/github/reygnn/rechts/TESTING_CONVENTIONS.kt` — the
   technical reference. Mandatory before touching any test.
2. **Then** an existing test as a template:
   - pure logic → `UrlNormalizerTest.kt`
   - ViewModel / Flow state → `BrowserViewModelTest.kt`

### Common tasks and where they go

- New address-bar parsing rule → `UrlNormalizerTest.kt` (and the production
  change in `domain/UrlNormalizer.kt`).
- New chrome state transition → `BrowserViewModelTest.kt`.

---

## Anti-patterns we've already paid for

The full catalogue lives in `TESTING_CONVENTIONS.kt`. Most relevant here:

- **`MutableSharedFlow` buffer trap** — `loadRequests` has `replay = 0`.
  Observe it with Turbine (`vm.loadRequests.test { … }`), subscribing
  *before* calling `onAddressSubmit()`; a late collector misses the
  emission.
- **Dispatcher mix-up** — drive `runTest` with `mainRule.dispatcher`, never
  a second standalone dispatcher.
