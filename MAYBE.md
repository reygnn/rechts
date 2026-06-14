# MAYBE.md

Deferred ideas that are deliberately **not** built yet — kept here so the
reasoning isn't lost and a future change starts from the trade-offs, not from
scratch. Nothing here is a commitment. Anything graduating from this file needs
the usual explicit agreement (see `WHY_CLAUDE.md`) and, where it touches the
load-bearing invariants, a `CLAUDE.md` hard-rule update.

---

## Mode switch: keep the normal WebView alive instead of `saveState`/`restoreState`

**Status:** considered, not built. Today's behaviour ships the lower-cost option.

### What ships today

Switching modes rebuilds the WebView on the other profile. The normal session
survives a `normal → private → normal` round trip via an **in-memory**
`WebView.saveState` snapshot (`NormalHistoryHolder`), restored with
`restoreState` on the way back (see `CLAUDE.md` hard rule 5). Private always
opens the homepage and is never captured (hard rule 8).

Platform ceiling of that approach: `restoreState` **reloads** the current page
and restores only the page + back/forward list — **scroll position and form
input are lost**, and there's a network round-trip on the way back.

### The higher-fidelity alternative

Don't destroy the normal WebView when entering private mode. **Detach it, pause
it, and keep it in memory**, then re-attach it on the way back instead of
building a fresh one and replaying `restoreState`.

What it buys: **perfect fidelity** — scroll position, form input, in-page JS
state, media, and the live DOM all survive the excursion, with **no reload**.
That's strictly better UX than the save/restore round trip.

### Why it's deferred (the costs)

- **Two live WebViews during a private session.** The paused normal WebView
  holds memory (its page, JS heap, image cache) for as long as the user stays
  in private mode. For a deliberately minimal browser that's a real regression
  in the resource story.
- **Breaks the "only one WebView is ever active" invariant.** Several places
  lean on it today — e.g. `pauseTimers()` is process-global and is used
  precisely *because* only one view runs (see the lifecycle `DisposableEffect`
  in `BrowserScreen`). With two instances we'd need per-view timer/lifecycle
  management and to guarantee the hidden one truly renders/runs nothing.
- **More lifecycle complexity.** The hidden WebView must be paused
  (`onPause` + `pauseTimers`) without pausing the active one, kept off the view
  tree, and destroyed correctly on screen exit / process death — without
  reintroducing the attach/detach `destroy()` gotchas the current single-view
  teardown already handles.
- **Privacy invariants must hold unchanged.** Keeping the normal view alive is
  privacy-neutral in principle (it's the persistent default profile, paused),
  but hard rules 3 and 8 must stay literally true: private is still its own
  isolated profile, still never persisted, still always opens the homepage.

### Rough implementation sketch (if it ever graduates)

- Hold **both** instances screen-scoped: keep the normal WebView in a holder
  rather than letting `remember(isPrivate, …)` drop it; build the private one
  on demand.
- On `onTogglePrivate`: detach the current view from its parent, `onPause()` +
  `pauseTimers()` it, stash it; attach/resume the other instance (build it if
  absent). `AndroidView`'s `factory`/`update` + a `key` on the *active* view.
- Per-view lifecycle: the active view follows `LocalLifecycleOwner`; the stashed
  one stays paused regardless. Re-check that `resumeTimers()`/`pauseTimers()`
  (process-global) do the right thing with two instances.
- Teardown: destroy **both** (detach-then-`destroy()` each) on screen exit; the
  private one's profile is still wiped per hard rule 3.
- Keep `saveState`/`restoreState` only for the cases a live instance can't cover
  (process death), i.e. the disk saver stays.

### Decision rule for revisiting

Build this only if users actually hit the lost-scroll/lost-form pain often
enough to justify the second live WebView and the lifecycle complexity. Until
then, the save/restore approach is the right fit for "minimal".
