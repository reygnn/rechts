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

---

Open issue from the code review of the downloads feature (`v0.6.0`, the
`a719362..8595818` range). The other findings (#2 misleading "unsupported"
notice, #3 duplicated scheme parsing, #4 triplicated profile selection) were
fixed in 0.6.1.

## Bugs

- [ ] **A download whose filename already exists in `Downloads/` fails
  silently after an optimistic "Download started…".**
  `web/DownloadHandler.kt` (`enqueue` → `setDestinationInExternalPublicDir`).
  We force an explicit destination path (`Downloads/<guessFileName>`); when that
  file already exists the platform `DownloadManager` does **not** auto-number
  (`file-1.zip`) for an explicit destination — it fails the download with
  `ERROR_FILE_ALREADY_EXISTS`. The failure is **asynchronous**: `enqueue()` is
  accepted and returns a valid id, so we report `Outcome.STARTED` and toast
  "Download started…"; the error only surfaces later on the manager's background
  thread, where our `try/catch` can't see it. Result: the user is told it
  started but no file lands (only the system's own failure notification shows).
  Repro: download `bild.jpg`, then re-download anything that `guessFileName`
  resolves to `bild.jpg` again.

  **Deliberately deferred** (decided during the 0.6.1 review): under scoped
  storage (`minSdk 36`) there is no *reliable* pre-check — files DownloadManager
  writes to public `Downloads/` are owned by the download provider, so a
  `File.exists()` / MediaStore query only sees rechts' *own* prior downloads,
  not the real directory contents. The genuinely correct fix is to observe the
  real outcome via a `DownloadManager` completion broadcast
  (`ACTION_DOWNLOAD_COMPLETE`) and show a real "failed" notice — but that is a
  small feature (receiver in `MainActivity`, manifest entry, lifecycle) and was
  judged out of scope for the minimal no-download-UI design. The system still
  posts its own failure notification, so it is not fully invisible.
