package com.github.reygnn.rechts.web

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import androidx.core.net.toUri

/**
 * Hands a WebView download off to the system [DownloadManager].
 *
 * rechts has no download UI of its own (CLAUDE.md → minimal scope): when the
 * WebView signals a download (a `Content-Disposition: attachment`, or a MIME
 * type it can't render), the file is enqueued with the platform
 * [DownloadManager], which fetches it on its own thread, writes it into the
 * public Downloads collection, and shows the system progress/"done"
 * notification. No storage permission is needed — DownloadManager is exempt for
 * the public Downloads directory.
 *
 * The browsing session is preserved by forwarding the page's cookies and
 * user-agent on the request, so logged-in downloads authenticate. In private
 * mode the caller passes the isolated profile's cookies (see
 * [WebViewConfig.cookieFor]); the file itself still lands in public storage,
 * which is inherent to "download".
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"

    /**
     * Outcome of an [enqueue] attempt, so the caller can show an accurate
     * notice instead of conflating two very different cases:
     *  - [STARTED]: handed to the system DownloadManager (its notification
     *    takes over from here).
     *  - [UNSUPPORTED]: the URL has no fetchable form (a `blob:`/`data:` or
     *    other non-`http(s)` download built client-side) — nothing rechts can
     *    do; the "unsupported" notice is the honest answer.
     *  - [FAILED]: a perfectly valid `http(s)` download the platform refused
     *    (no DownloadManager service, or `enqueue` threw). The link *is*
     *    supported, so "unsupported" would misdiagnose it — say it failed.
     */
    enum class Outcome { STARTED, UNSUPPORTED, FAILED }

    /**
     * Whether [url] is something [DownloadManager] can fetch. It speaks only
     * `http`/`https` (and `ftp`, irrelevant here); a `blob:`/`data:` download
     * built client-side in JavaScript has no fetchable URL and can't be
     * enqueued — the caller then tells the user it's unsupported. Pure (no
     * Android side effects) so it stays JVM-testable, like
     * [ExternalScheme.isInternal].
     */
    fun isEnqueueable(url: String): Boolean {
        val scheme = schemeOf(url)
        return scheme == "http" || scheme == "https"
    }

    /**
     * Enqueues a system download for [url]. Returns [Outcome.STARTED] when the
     * DownloadManager accepted it, [Outcome.UNSUPPORTED] when the URL can't be
     * downloaded this way (non-http(s)), or [Outcome.FAILED] when a valid
     * http(s) download was refused (no DownloadManager, or enqueue threw). The
     * caller picks the matching notice — never failing silently, and never
     * mislabelling a refused-but-valid download as "unsupported".
     */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookie: String?,
    ): Outcome {
        if (!isEnqueueable(url)) return Outcome.UNSUPPORTED
        val downloadManager =
            context.getSystemService(DownloadManager::class.java) ?: return Outcome.FAILED
        val fileName = guessFileName(url, contentDisposition, mimeType)
        return try {
            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                // Carry the page's session so a download behind a login works;
                // in private mode this is the isolated profile's cookie string.
                cookie?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            downloadManager.enqueue(request)
            Outcome.STARTED
        } catch (e: RuntimeException) {
            // enqueue() / setDestinationInExternalPublicDir() can throw —
            // IllegalStateException if the Downloads dir is unavailable, or a
            // SecurityException from an odd device provider. A bad download must
            // never crash the browser; report failure and let the caller notify.
            Log.w(TAG, "Could not enqueue download for: $url", e)
            Outcome.FAILED
        }
    }

    /**
     * The file name the system download will be saved under, from the same
     * [URLUtil.guessFileName] [enqueue] uses. Exposed so the duplicate check and
     * the "already downloaded" prompt name the file exactly as it will be
     * written (and matched against the download history, which stores it as the
     * request title).
     */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String =
        URLUtil.guessFileName(url, contentDisposition, mimeType)

    /**
     * File names of the app's own previously-completed downloads, read from the
     * system [DownloadManager] (which stores each as the request title — see
     * `setTitle` in [enqueue]). Used to warn before re-downloading a file rechts
     * already fetched.
     *
     * Permission-free by design: [DownloadManager] only ever exposes the calling
     * app's own downloads, so this is scoped to rechts' history. Two honest
     * limitations follow — it can't see files other apps placed in `Downloads/`,
     * and a file the user has since deleted still lingers as a history row — so
     * treat a hit as "rechts downloaded this name before", not "the file is on
     * disk right now". Never throws: a failed query yields an empty set (→ no
     * prompt, download proceeds) rather than blocking the download.
     */
    fun existingDownloadNames(context: Context): Set<String> {
        val downloadManager =
            context.getSystemService(DownloadManager::class.java) ?: return emptySet()
        val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        return runCatching {
            downloadManager.query(query)?.use { cursor ->
                val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                if (titleColumn < 0) return@use emptySet()
                buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(titleColumn)?.let { add(it) }
                    }
                }
            } ?: emptySet()
        }.getOrElse {
            Log.w(TAG, "Could not read download history", it)
            emptySet()
        }
    }

    /**
     * Whether [fileName] matches one of [existingNames] — i.e. rechts has already
     * downloaded a file of that exact name. Pure, so the duplicate decision is
     * JVM-tested; the caller reads [existingNames] from [existingDownloadNames].
     */
    fun isDuplicate(existingNames: Set<String>, fileName: String): Boolean =
        fileName in existingNames
}
