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
  MinIntervalGateTest.kt        — pure: toast-throttle gate (time passed in, no SystemClock)
web/
  ExternalSchemeTest.kt         — pure: isInternal + decideWebContentNavigation (gesture gate)
  ExternalSchemeFallbackTest.kt — Robolectric: intent: browser_fallback_url parsing
  ExternalSchemeDispatchTest.kt — Robolectric: dispatch intent hardening (component/selector/BROWSABLE)
util/
  MainDispatcherRule.kt         — sets Dispatchers.Main = UnconfinedTestDispatcher
```

Mostly JVM-only; `ExternalSchemeFallbackTest` needs Robolectric (real
`Intent.parseUri`). Run with `./gradlew testDebugUnitTest`.

---

## Scope & tooling choices

- **Pure logic still lives outside the `WebView`.** The first move for anything
  worth pinning (parsing, state transitions, decisions) is still to push it
  *out* of the WebView into a pure helper, `UrlNormalizer`, or
  `BrowserViewModel` and test that with a fast JVM test — see `CrashReloadGate`
  / `MinIntervalGate`. That keeps most of the suite Robolectric-free.
- **Robolectric is allowed where the Android runtime is genuinely exercised.**
  It is a sanctioned tool here, not a last resort — use it when the behaviour
  worth pinning only exists on the Android runtime (real `Intent.parseUri`,
  `android.net.Uri`, and yes, `BrowserScreen` / `WebView` wiring such as the
  scheme gate, profile binding, or crash-rebuild flow). Existing examples:
  `ExternalSchemeFallbackTest` and `ExternalSchemeDispatchTest`. Keep
  Robolectric tests in their own class (`@RunWith(RobolectricTestRunner::class)`,
  `@Config(sdk = [36])`) so the pure suites stay fast, and still prefer a pure
  test when one would cover the logic just as well — speed, not prohibition, is
  the reason.
- **No androidTest source set.** Robolectric covers the Android-runtime cases;
  add an instrumented source set only if a real device/emulator is genuinely
  required.

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

- **`loadRequests` is a buffered `Channel` (via `receiveAsFlow`)**, not a
  `replay = 0` SharedFlow — a request submitted before the collector
  attaches is buffered and still delivered (there's a test for exactly
  that). Observe it with Turbine (`vm.loadRequests.test { … }`). Note a
  channel-backed flow is single-collection; each test uses a fresh VM.
- **Dispatcher mix-up** — drive `runTest` with `mainRule.dispatcher`, never
  a second standalone dispatcher.
