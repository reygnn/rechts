package com.github.reygnn.rechts.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil

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
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
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
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
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
}
