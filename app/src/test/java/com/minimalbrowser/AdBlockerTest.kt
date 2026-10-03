package com.minimalbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdBlockerTest {

    private lateinit var blocker: AdBlocker

    @Before
    fun setUp() {
        blocker = AdBlocker.forTesting(
            hosts = setOf("ads.example.com", "doubleclick.net", "tracker.io"),
            patterns = setOf("/ads/", "pixel.gif", "/analytics")
        )
    }

    @Test fun blocksExactHost() {
        assertTrue(blocker.isBlocked("https://ads.example.com/banner.png"))
    }

    @Test fun blocksSubdomainViaParentRule() {
        assertTrue(blocker.isBlocked("https://a.b.c.doubleclick.net/x"))
    }

    @Test fun doesNotBlockUnrelatedHost() {
        assertFalse(blocker.isBlocked("https://example.com/index.html"))
    }

    @Test fun doesNotBlockHostThatMerelyContainsKeyword() {
        assertFalse(blocker.isBlocked("https://notracker.io.example.com/"))
    }

    @Test fun blocksUrlByKeywordPattern() {
        assertTrue(blocker.isBlocked("https://cdn.example.com/ads/banner.png"))
    }

    @Test fun blocksUrlByFilenamePattern() {
        assertTrue(blocker.isBlocked("https://example.com/img/pixel.gif"))
    }

    @Test fun doesNotBlockCleanUrl() {
        assertFalse(blocker.isBlocked("https://example.com/articles/1"))
    }

    @Test fun handlesMalformedUrlGracefully() {
        assertFalse(blocker.isBlocked("not a url"))
    }

    @Test fun doesNotBlockDomainThatOnlySharesASuffix() {
        assertFalse(blocker.isBlocked("https://notdoubleclick.net/x"))
    }

    @Test fun repeatedCallsReturnConsistentResult() {
        val url = "https://ads.example.com/banner.png"
        assertTrue(blocker.isBlocked(url))
        assertTrue(blocker.isBlocked(url))
        assertTrue(blocker.isBlocked(url))
    }
}
