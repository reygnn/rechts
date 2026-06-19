# TODO

Open issues from the code review of the favorites feature (`0668217..HEAD`).
Ranked most-severe first.

## Bugs

- [x] **Drag reorder snaps back when a state emission lands mid-drag.**
  `ui/favorites/FavoritesScreen.kt` (`FavoritesList` `update` → `submit`).
  `favorites: List<Favorite>` is an unstable Compose param, so `FavoritesScreen`
  is non-skippable and recomposes on every `BrowserUiState` emission from the
  still-live WebView underneath (progress ticks, `onReceivedTitle`). Opening
  Favorites while a page is still loading and then dragging a row re-runs
  `AndroidView.update` → `adapter.submit(favorites)`; mid-drag `items` is
  reordered but not yet persisted, so `items != favorites`, the short-circuit
  misses, and `notifyDataSetChanged()` recreates view holders under the active
  `ItemTouchHelper` drag — the row snaps back. The guard only covers the
  post-drop echo. Fix: ignore submissions while a drag is active (or move to
  `DiffUtil`/`ListAdapter`).

- [x] **Favoriting before the title callback saves the bare URL as the name.**
  `ui/browser/BrowserScreen.kt` (`onToggleFavorite`) + `BrowserViewModel.kt`
  (`onPageStarted` resets `pageTitle = ""`). `onReceivedTitle` repopulates the
  title later — often after the page has visibly committed. Starring in that
  window persists `state.pageTitle.ifBlank { state.currentUrl }` = the URL, so
  the favorite's title line equals its url line. Re-starring only removes the
  entry, so the wrong title sticks. Fix: read `webView.title` at save time
  instead of the mirrored state.

- [x] **`onReceivedTitle` can crash on a null title.**
  `ui/browser/BrowserScreen.kt:662`. The override declares non-null
  `title: String`, but the SDK-36 stub carries no nullability annotation on the
  parameter (Kotlin platform type), unlike the `@Nullable` `WebViewClient`
  params. A null title (title-less document, some error/`about:` pages) triggers
  `checkNotNullParameter` → `IllegalArgumentException` on the UI thread. Fix:
  `title: String?` + `viewModel.onPageTitle(title.orEmpty())`.

## Cleanup / efficiency

- [x] **No-op drag still writes to DataStore.**
  `ui/favorites/FavoritesScreen.kt` (`clearView`). `onReordered(...)` fires on
  every drop, including a row dropped back in its original slot, triggering a
  full decode → re-encode → fsync for a byte-identical list. Guard on whether
  any `onMove` actually happened.

- [x] **`FavoritesOps.move` is dead in production; the shipped reorder is
  untested.** `domain/FavoritesOps.kt:56` vs `ui/favorites/FavoritesScreen.kt`
  (`FavoritesAdapter.moveItem`). The pure, JVM-tested `move` is never called;
  the real reorder path lives in the adapter and has no test. Route `saveOrder`
  through `FavoritesOps.move`, or delete the dead function.

- [x] **`isCurrentFavorite` re-scans the whole list every recomposition.**
  `ui/browser/BrowserScreen.kt:484`. `FavoritesOps.isFavorite(...)` runs an O(n)
  scan (n ≤ 100) in the composable body, re-firing on every progress-tick
  recomposition during load. Wrap in `remember(favorites, state.currentUrl)`.

- [x] **Cap doc/code mismatch.** `domain/FavoritesOps.kt`. The `MAX_FAVORITES`
  cap is applied in both `encode` (:67) and `decode` (:79), but the KDoc says it
  is "Enforced on `encode`." Harmless, but the doc and the second `takeLast`
  disagree on where the cap is authoritative.
