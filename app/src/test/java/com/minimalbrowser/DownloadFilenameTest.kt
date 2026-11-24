package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the download-filename bug fix (Content-Disposition must win
 * over a URL-derived name) and the RFC 5987 extended-notation parsing
 * that goes with it. Pure Kotlin / java.io only, no Android dependency.
 */
class DownloadFilenameTest {

    private fun newTempDir(): File = Files.createTempDirectory("mb-dl-test-").toFile()

    // -------------------------------------------------------------------
    // guessFilename: Content-Disposition now wins over the URL
    // -------------------------------------------------------------------

    @Test
    fun `Content-Disposition filename wins over URL-derived name`() {
        val name = DownloadHandler.guessFilename(
            url = "https://cdn.example.com/dl/a1b2c3d4?sig=xyz",
            contentDisposition = "attachment; filename=\"report.pdf\"",
            mimeType = "application/pdf"
        )
        assertEquals("report.pdf", name)
    }

    @Test
    fun `URL filename used when no Content-Disposition is present`() {
        val name = DownloadHandler.guessFilename(
            url = "https://example.com/files/photo.png",
            contentDisposition = null,
            mimeType = "image/png"
        )
        assertEquals("photo.png", name)
    }

    @Test
    fun `falls back to a generated name when neither source has one`() {
        val name = DownloadHandler.guessFilename(
            url = "https://example.com/download",
            contentDisposition = null,
            mimeType = "application/pdf"
        )
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.startsWith("download_"))
    }

    @Test
    fun `blank Content-Disposition falls back to URL name`() {
        val name = DownloadHandler.guessFilename(
            url = "https://example.com/files/doc.txt",
            contentDisposition = "",
            mimeType = "text/plain"
        )
        assertEquals("doc.txt", name)
    }

    // -------------------------------------------------------------------
    // parseContentDispositionFilename: filename* wins over plain filename
    // -------------------------------------------------------------------

    @Test
    fun `extended filename star wins when both forms are present`() {
        val cd = "attachment; filename=\"fallback.txt\"; " +
            "filename*=UTF-8''%e6%97%a5%e6%9c%ac.txt"
        val name = DownloadHandler.parseContentDispositionFilename(cd)
        // U+65E5 U+672C = "日本"
        assertEquals("\u65E5\u672C.txt", name)
    }

    @Test
    fun `plain filename is used when no extended form is present`() {
        val cd = "attachment; filename=\"invoice-2026.pdf\""
        assertEquals("invoice-2026.pdf", DownloadHandler.parseContentDispositionFilename(cd))
    }

    @Test
    fun `plain filename is not plus-decoded (RFC 5987 vs x-www-form-urlencoded)`() {
        // A literal '+' in a plain filename= must survive untouched. An
        // earlier version ran this through URLDecoder.decode, which
        // turns '+' into a space -- this is the subtle bug that version
        // introduced while trying to handle the extended form.
        val cd = "attachment; filename=\"C++ notes.txt\""
        assertEquals("C++ notes.txt", DownloadHandler.parseContentDispositionFilename(cd))
    }

    @Test
    fun `extended form percent-decodes without plus-as-space`() {
        // RFC 5987 payloads never encode space as '+'; a literal '+' in
        // the encoded value must also survive untouched.
        val cd = "filename*=UTF-8''a%2Bb%20c.txt"
        assertEquals("a+b c.txt", DownloadHandler.parseContentDispositionFilename(cd))
    }

    @Test
    fun `missing Content-Disposition returns null`() {
        assertNull(DownloadHandler.parseContentDispositionFilename(null))
        assertNull(DownloadHandler.parseContentDispositionFilename(""))
        assertNull(DownloadHandler.parseContentDispositionFilename("attachment"))
    }

    // -------------------------------------------------------------------
    // urlFileNameCandidate: pure replacement for Uri.parse(...).lastPathSegment
    // -------------------------------------------------------------------

    @Test
    fun `url filename candidate strips query and fragment`() {
        assertEquals(
            "file.zip",
            DownloadHandler.urlFileNameCandidate(
                "https://example.com/a/b/file.zip?token=abc#frag"
            )
        )
    }

    @Test
    fun `url filename candidate requires a dot (extension-like)`() {
        // Opaque ids with no extension should not be treated as a
        // filename -- this is what forces the fallback to a generated
        // name in guessFilename when the URL's last segment is not
        // filename-shaped.
        assertNull(
            DownloadHandler.urlFileNameCandidate("https://example.com/dl/a1b2c3d4")
        )
    }

    @Test
    fun `url filename candidate percent-decodes`() {
        assertEquals(
            "my file.txt",
            DownloadHandler.urlFileNameCandidate("https://example.com/my%20file.txt")
        )
    }

    // -------------------------------------------------------------------
    // sanitize
    // -------------------------------------------------------------------

    @Test
    fun `sanitize strips path separators`() {
        assertEquals("etc_passwd", DownloadHandler.sanitize("../etc/passwd"))
        assertEquals("a_b", DownloadHandler.sanitize("a\\b"))
    }

    @Test
    fun `sanitize falls back to a default name for degenerate input`() {
        assertEquals("download", DownloadHandler.sanitize(""))
        assertEquals("download", DownloadHandler.sanitize("."))
        assertEquals("download", DownloadHandler.sanitize(".."))
    }

    @Test
    fun `sanitize truncates very long names while keeping the extension`() {
        val long = "a".repeat(300) + ".pdf"
        val result = DownloadHandler.sanitize(long)
        assertTrue(result.length <= 180)
        assertTrue(result.endsWith(".pdf"))
    }

    // -------------------------------------------------------------------
    // uniqueFile
    // -------------------------------------------------------------------

    @Test
    fun `uniqueFile returns the plain name when nothing exists`(){
        val dir = newTempDir()
        try {
            val f = DownloadHandler.uniqueFile(dir, "report.pdf")
            assertEquals("report.pdf", f.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueFile never overwrites an existing file`() {
        val dir = newTempDir()
        try {
            File(dir, "report.pdf").writeText("original")
            val f = DownloadHandler.uniqueFile(dir, "report.pdf")
            assertEquals("report (1).pdf", f.name)
            assertTrue(!f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueFile increments past multiple collisions`() {
        val dir = newTempDir()
        try {
            File(dir, "report.pdf").writeText("x")
            File(dir, "report (1).pdf").writeText("x")
            File(dir, "report (2).pdf").writeText("x")
            val f = DownloadHandler.uniqueFile(dir, "report.pdf")
            assertEquals("report (3).pdf", f.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueFile handles names with no extension`() {
        val dir = newTempDir()
        try {
            File(dir, "README").writeText("x")
            val f = DownloadHandler.uniqueFile(dir, "README")
            assertEquals("README (1)", f.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
