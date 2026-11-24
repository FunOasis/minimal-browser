package com.minimalbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers shouldFreezeTab(), the pure decision function extracted from
 * TabManager.freezeIdleTabs() so the idle-threshold arithmetic and the
 * active-tab-is-protected-in-the-foreground rule can be tested without
 * a real WebView or Activity.
 */
class TabFreezeTest {

    private val oneMinuteMs = 60_000L

    @Test
    fun `background tab past the foreground threshold is frozen`() {
        val now = 1_000_000L
        val lastUsed = now - 11 * oneMinuteMs
        assertTrue(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = false,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }

    @Test
    fun `background tab not yet past the threshold is not frozen`() {
        val now = 1_000_000L
        val lastUsed = now - 5 * oneMinuteMs
        assertFalse(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = false,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }

    @Test
    fun `active tab in the foreground is never frozen regardless of idle time`() {
        val now = 1_000_000L
        val lastUsed = now - 999 * oneMinuteMs
        assertFalse(
            shouldFreezeTab(
                isActive = true,
                isBackgrounded = false,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }

    @Test
    fun `active tab becomes eligible once the app itself is backgrounded`() {
        val now = 1_000_000L
        val lastUsed = now - 3 * oneMinuteMs
        assertTrue(
            shouldFreezeTab(
                isActive = true,
                isBackgrounded = true,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }

    @Test
    fun `a threshold of zero or less means never freeze`() {
        val now = 1_000_000L
        val lastUsed = now - 999 * oneMinuteMs
        assertFalse(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = false,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 0,
                freezeIdleBackgroundMinutes = 2
            )
        )
        assertFalse(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = true,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 0
            )
        )
    }

    @Test
    fun `exactly at the threshold counts as idle (inclusive boundary)`() {
        val now = 1_000_000L
        val lastUsed = now - 10 * oneMinuteMs
        assertTrue(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = false,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }

    @Test
    fun `backgrounded app uses the background threshold, not the foreground one`() {
        val now = 1_000_000L
        // 3 minutes idle: past the 2-minute background threshold, but
        // well under the 10-minute foreground threshold -- this only
        // freezes if isBackgrounded correctly selects the background
        // minutes.
        val lastUsed = now - 3 * oneMinuteMs
        assertTrue(
            shouldFreezeTab(
                isActive = false,
                isBackgrounded = true,
                lastUsedAt = lastUsed,
                now = now,
                freezeIdleForegroundMinutes = 10,
                freezeIdleBackgroundMinutes = 2
            )
        )
    }
}
