package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers BlobDownloadHelper's pure, Android-free helpers:
 *   - sanitize(): shared pattern with DownloadHandler.sanitize, used on
 *     the page-supplied blob filename.
 *   - mimeToExt(): used to name a data: URL download when no explicit
 *     filename is given.
 *   - parseDataUrlHeader(): the header/payload split that makes the
 *     streaming data: URL decode (this patch's "avoid full data-URL
 *     buffering" fix) possible without ever touching android.util.Base64
 *     or android.net.Uri, so it is directly testable here.
 *
 * The truncation check in deliverChunk() and the concurrency fix in
 * start() both depend on android.os.Handler/ConcurrentHashMap wiring
 * that is exercised by inspection rather than by a unit test here (see
 * CHANGES.md for why Robolectric-based tests were deliberately left out
 * of this patch).
 */
class BlobDownloadHelperTest {

    // -------------------------------------------------------------------
    // sanitize
    // -------------------------------------------------------------------

    @Test
    fun `sanitize strips path separators from a page-supplied name`() {
        assertEquals("etc_passwd", BlobDownloadHelper.sanitize("../etc/passwd"))
    }

    @Test
    fun `sanitize returns empty string for degenerate input (unlike DownloadHandler)`() {
        // BlobDownloadHelper.begin() treats an empty sanitize() result as
        // "keep the existing fallback name" rather than substituting a
        // generic "download" stem, so this intentionally differs from
        // DownloadHandler.sanitize()'s behaviour.
        assertEquals("", BlobDownloadHelper.sanitize(""))
        assertEquals("", BlobDownloadHelper.sanitize("."))
        assertEquals("", BlobDownloadHelper.sanitize(".."))
    }

    @Test
    fun `sanitize truncates very long names while keeping the extension`() {
        val long = "b".repeat(300) + ".png"
        val result = BlobDownloadHelper.sanitize(long)
        assertTrue(result.length <= 180)
        assertTrue(result.endsWith(".png"))
    }

    // -------------------------------------------------------------------
    // mimeToExt
    // -------------------------------------------------------------------

    @Test
    fun `mimeToExt maps common types`() {
        assertEquals("png", BlobDownloadHelper.mimeToExt("image/png"))
        assertEquals("jpg", BlobDownloadHelper.mimeToExt("image/jpeg"))
        assertEquals("pdf", BlobDownloadHelper.mimeToExt("application/pdf"))
        assertEquals("json", BlobDownloadHelper.mimeToExt("application/json"))
    }

    @Test
    fun `mimeToExt is case-insensitive`() {
        assertEquals("png", BlobDownloadHelper.mimeToExt("IMAGE/PNG"))
    }

    @Test
    fun `mimeToExt falls back to the subtype for unknown mimes`() {
        assertEquals("rtf", BlobDownloadHelper.mimeToExt("application/rtf"))
    }

    @Test
    fun `mimeToExt falls back to bin for totally unrecognisable input`() {
        assertEquals("bin", BlobDownloadHelper.mimeToExt(""))
    }

    // -------------------------------------------------------------------
    // parseDataUrlHeader
    // -------------------------------------------------------------------

    @Test
    fun `parses a base64 data URL header`() {
        val header = BlobDownloadHelper.parseDataUrlHeader(
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
        )
        requireNotNull(header)
        assertEquals("image/png", header.mime)
        assertTrue(header.isBase64)
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", header.payload)
    }

    @Test
    fun `parses a non-base64 (percent-encoded) data URL header`() {
        val header = BlobDownloadHelper.parseDataUrlHeader("data:text/plain,Hello%20world")
        requireNotNull(header)
        assertEquals("text/plain", header.mime)
        assertFalse(header.isBase64)
        assertEquals("Hello%20world", header.payload)
    }

    @Test
    fun `defaults to octet-stream when no mime type is given`() {
        val header = BlobDownloadHelper.parseDataUrlHeader("data:;base64,AAAA")
        requireNotNull(header)
        assertEquals("application/octet-stream", header.mime)
        assertTrue(header.isBase64)
    }

    @Test
    fun `ignores a charset parameter when extracting the mime type`() {
        val header = BlobDownloadHelper.parseDataUrlHeader(
            "data:text/plain;charset=utf-8;base64,SGVsbG8="
        )
        requireNotNull(header)
        assertEquals("text/plain", header.mime)
        assertTrue(header.isBase64)
    }

    @Test
    fun `returns null for a non data URL`() {
        assertNull(BlobDownloadHelper.parseDataUrlHeader("https://example.com/x"))
    }

    @Test
    fun `returns null when there is no comma separating header from payload`() {
        assertNull(BlobDownloadHelper.parseDataUrlHeader("data:text/plain;base64"))
    }

    @Test
    fun `default name extension matches the parsed mime type`() {
        val header = BlobDownloadHelper.parseDataUrlHeader(
            "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        )
        requireNotNull(header)
        assertTrue(header.defaultName.endsWith(".jpg"))
    }
}
