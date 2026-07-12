package com.github.reygnn.rechts.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions in [DownloadHandler] are unit-tested: [isEnqueueable]
 * (whether a URL can go to the system DownloadManager at all) and [isDuplicate]
 * (whether a file of that name was downloaded before). [enqueue],
 * [guessFileName] and [existingDownloadNames] need the Android runtime
 * (DownloadManager, URLUtil) and are left to manual/instrumented verification,
 * like the rest of `web/`.
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

    @Test
    fun `isDuplicate matches an existing download name exactly`() {
        val existing = setOf("report.pdf", "photo.jpg")
        assertTrue(DownloadHandler.isDuplicate(existing, "report.pdf"))
        assertTrue(DownloadHandler.isDuplicate(existing, "photo.jpg"))
    }

    @Test
    fun `isDuplicate is exact and case-sensitive, and empty history never matches`() {
        val existing = setOf("report.pdf")
        // A different name, or a case variant, is not the same saved file.
        assertFalse(DownloadHandler.isDuplicate(existing, "other.zip"))
        assertFalse(DownloadHandler.isDuplicate(existing, "Report.pdf"))
        // No prior downloads → nothing is ever a duplicate (dialog never shows).
        assertFalse(DownloadHandler.isDuplicate(emptySet(), "report.pdf"))
    }
}
