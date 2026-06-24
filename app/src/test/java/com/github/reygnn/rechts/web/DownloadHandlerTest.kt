package com.github.reygnn.rechts.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only [DownloadHandler.isEnqueueable] is unit-tested: it is pure string logic
 * deciding whether a download URL can go to the system DownloadManager (http(s))
 * or must fall back to the "unsupported" notice (blob:/data:/…).
 * [DownloadHandler.enqueue] needs the Android runtime (DownloadManager, URLUtil)
 * and is left to manual/instrumented verification, like the rest of `web/`.
 */
class DownloadHandlerTest {

    @Test
    fun `http and https are enqueueable`() {
        assertTrue(DownloadHandler.isEnqueueable("http://example.com/file.zip"))
        assertTrue(DownloadHandler.isEnqueueable("https://example.com/a/b/file.pdf?x=1"))
    }

    @Test
    fun `scheme matching is case-insensitive`() {
        assertTrue(DownloadHandler.isEnqueueable("HTTPS://example.com/file.zip"))
        assertTrue(DownloadHandler.isEnqueueable("Http://example.com/file.zip"))
    }

    @Test
    fun `client-built downloads are not enqueueable`() {
        // blob:/data: have no fetchable URL for DownloadManager.
        assertFalse(DownloadHandler.isEnqueueable("blob:https://example.com/uuid"))
        assertFalse(DownloadHandler.isEnqueueable("data:text/plain;base64,SGk="))
    }

    @Test
    fun `other schemes are not enqueueable`() {
        assertFalse(DownloadHandler.isEnqueueable("ftp://example.com/file.zip"))
        assertFalse(DownloadHandler.isEnqueueable("file:///sdcard/x.txt"))
        assertFalse(DownloadHandler.isEnqueueable("javascript:alert(1)"))
    }

    @Test
    fun `missing scheme is not enqueueable`() {
        assertFalse(DownloadHandler.isEnqueueable("example.com/file.zip"))
        assertFalse(DownloadHandler.isEnqueueable(""))
    }
}
