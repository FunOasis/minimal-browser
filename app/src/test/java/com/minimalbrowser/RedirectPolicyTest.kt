package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the redirect cookie/referer leak fix in DownloadHandler. Cookie
 * forwarding itself is delegated to CookieManager (always re-queried
 * per hop -- see DownloadHandler.runDownload's doc), which cannot be
 * exercised without the Android framework, so this file covers the
 * referrer policy and origin classification that ARE pure. Also covers
 * clampProgressPercent, the other pure helper in the same file.
 */
class RedirectPolicyTest {

    // -------------------------------------------------------------------
    // classifyRedirect
    // -------------------------------------------------------------------

    @Test
    fun `same host and scheme is SAME_ORIGIN`() {
        assertEquals(
            DownloadHandler.OriginRelation.SAME_ORIGIN,
            DownloadHandler.classifyRedirect(
                "https://example.com/a", "https://example.com/b"
            )
        )
    }

    @Test
    fun `same host, path only, is SAME_ORIGIN`() {
        assertEquals(
            DownloadHandler.OriginRelation.SAME_ORIGIN,
            DownloadHandler.classifyRedirect(
                "https://example.com/page", "https://example.com/page"
            )
        )
    }

    @Test
    fun `different host, no downgrade, is CROSS_SITE`() {
        assertEquals(
            DownloadHandler.OriginRelation.CROSS_SITE,
            DownloadHandler.classifyRedirect(
                "https://example.com/a", "https://cdn-other.net/b"
            )
        )
    }

    @Test
    fun `same host but https to http is SAME_HOST_DOWNGRADE`() {
        assertEquals(
            DownloadHandler.OriginRelation.SAME_HOST_DOWNGRADE,
            DownloadHandler.classifyRedirect(
                "https://example.com/a", "http://example.com/a"
            )
        )
    }

    @Test
    fun `different host and https to http is DOWNGRADE_CROSS_SITE`() {
        assertEquals(
            DownloadHandler.OriginRelation.DOWNGRADE_CROSS_SITE,
            DownloadHandler.classifyRedirect(
                "https://example.com/a", "http://evil.example/b"
            )
        )
    }

    @Test
    fun `http to https upgrade on a different host is CROSS_SITE not a downgrade`() {
        assertEquals(
            DownloadHandler.OriginRelation.CROSS_SITE,
            DownloadHandler.classifyRedirect(
                "http://example.com/a", "https://other.com/b"
            )
        )
    }

    @Test
    fun `subdomain is a different host, not SAME_ORIGIN`() {
        assertEquals(
            DownloadHandler.OriginRelation.CROSS_SITE,
            DownloadHandler.classifyRedirect(
                "https://example.com/a", "https://cdn.example.com/b"
            )
        )
    }

    // -------------------------------------------------------------------
    // refererForHop -- this is the actual leak fix: a cross-origin or
    // downgraded hop must never forward the full referring-page URL
    // (which can carry sensitive paths/query params) unchanged.
    // -------------------------------------------------------------------

    @Test
    fun `same-origin hop sends the full referrer unchanged`() {
        val referer = "https://shop.example.com/cart?items=abc123"
        assertEquals(
            referer,
            DownloadHandler.refererForHop(referer, "https://shop.example.com/download")
        )
    }

    @Test
    fun `cross-site hop sends only the origin, not the full referrer`() {
        val referer = "https://shop.example.com/cart?items=abc123"
        assertEquals(
            "https://shop.example.com",
            DownloadHandler.refererForHop(referer, "https://evil-redirect.example/x")
        )
    }

    @Test
    fun `any downgrade hop sends no referrer at all`() {
        val referer = "https://shop.example.com/cart?items=abc123"
        assertNull(
            DownloadHandler.refererForHop(referer, "http://shop.example.com/download")
        )
        assertNull(
            DownloadHandler.refererForHop(referer, "http://other-host.example/download")
        )
    }

    @Test
    fun `null or blank referring page yields no referrer for any hop`() {
        assertNull(DownloadHandler.refererForHop(null, "https://example.com/x"))
        assertNull(DownloadHandler.refererForHop("", "https://example.com/x"))
    }

    // -------------------------------------------------------------------
    // originOf
    // -------------------------------------------------------------------

    @Test
    fun `originOf strips path, query and fragment`() {
        assertEquals(
            "https://example.com",
            DownloadHandler.originOf("https://example.com/a/b?x=1#frag")
        )
    }

    @Test
    fun `originOf keeps a non-default port`() {
        assertEquals(
            "http://example.com:8080",
            DownloadHandler.originOf("http://example.com:8080/a")
        )
    }

    @Test
    fun `originOf omits the default port for the scheme`() {
        assertEquals(
            "https://example.com",
            DownloadHandler.originOf("https://example.com:443/a")
        )
    }

    // -------------------------------------------------------------------
    // clampProgressPercent
    // -------------------------------------------------------------------

    @Test
    fun `progress is clamped to 100 even if bytesWritten exceeds total`() {
        // A server that misreports Content-Length (or a declared length
        // estimate that undershoots reality) must never push the
        // progress bar past 100%.
        assertEquals(100, DownloadHandler.clampProgressPercent(150L, 100L))
    }

    @Test
    fun `progress is a normal percentage in the common case`() {
        assertEquals(50, DownloadHandler.clampProgressPercent(50L, 100L))
        assertEquals(0, DownloadHandler.clampProgressPercent(0L, 100L))
        assertEquals(100, DownloadHandler.clampProgressPercent(100L, 100L))
    }

    @Test
    fun `unknown total length yields -1, distinguishing it from 0 percent`() {
        assertEquals(-1, DownloadHandler.clampProgressPercent(0L, -1L))
        assertEquals(-1, DownloadHandler.clampProgressPercent(500L, 0L))
    }
}
