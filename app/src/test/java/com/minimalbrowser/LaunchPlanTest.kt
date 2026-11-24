package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers resolveLaunchPlan(), the pure decision function extracted from
 * MainActivity.onCreate (see LaunchPlan.kt). Exercises every
 * restore-source x shortcut x external-URL combination the real
 * onCreate can see on startup -- previously untested entirely, since
 * the logic lived inline in an Activity method. No Android dependency.
 */
class LaunchPlanTest {

    @Test
    fun `cold start with nothing saved and no intent extras opens a bare home tab`() {
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = null, diskActive = 0,
            shortcutUrl = null, externalUrl = null
        )
        assertEquals(LaunchPlan.CreateSingle(null), plan)
    }

    @Test
    fun `cold start with only a shortcut URL opens exactly that URL`() {
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = null, diskActive = 0,
            shortcutUrl = "https://shortcut.example.com", externalUrl = null
        )
        assertEquals(LaunchPlan.CreateSingle("https://shortcut.example.com"), plan)
    }

    @Test
    fun `cold start with only an external URL opens exactly that URL`() {
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = null, diskActive = 0,
            shortcutUrl = null, externalUrl = "https://shared-link.example.com"
        )
        assertEquals(LaunchPlan.CreateSingle("https://shared-link.example.com"), plan)
    }

    @Test
    fun `shortcut takes precedence over external URL when both are present and nothing is saved`() {
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = null, diskActive = 0,
            shortcutUrl = "https://shortcut.example.com",
            externalUrl = "https://shared-link.example.com"
        )
        assertEquals(LaunchPlan.CreateSingle("https://shortcut.example.com"), plan)
    }

    @Test
    fun `an in-memory saved state (config change) restores without touching disk`() {
        val saved = listOf("https://a.example.com", "https://b.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = saved, savedActive = 1, savedJs = listOf(true, false),
            diskUrls = listOf("https://stale-disk.example.com"), diskActive = 0,
            shortcutUrl = null, externalUrl = null
        )
        assertTrue(plan is LaunchPlan.Restore)
        val restore = plan as LaunchPlan.Restore
        assertEquals(saved, restore.urls)
        assertEquals(1, restore.activeIndex)
        assertEquals(listOf(true, false), restore.jsFlags)
        assertEquals(PostRestoreAction.None, restore.postAction)
    }

    @Test
    fun `an on-disk session is restored on cold start when no saved state exists`() {
        val disk = listOf("https://a.example.com", "https://b.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = disk, diskActive = 1,
            shortcutUrl = null, externalUrl = null
        )
        assertTrue(plan is LaunchPlan.Restore)
        val restore = plan as LaunchPlan.Restore
        assertEquals(disk, restore.urls)
        assertEquals(1, restore.activeIndex)
        assertEquals(PostRestoreAction.None, restore.postAction)
    }

    @Test
    fun `an empty saved-state list is treated as no saved state, falling through to disk`() {
        val disk = listOf("https://from-disk.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = emptyList(), savedActive = 5, savedJs = null,
            diskUrls = disk, diskActive = 0,
            shortcutUrl = null, externalUrl = null
        )
        assertTrue(plan is LaunchPlan.Restore)
        val restore = plan as LaunchPlan.Restore
        // Must use the disk snapshot's data, not the empty saved list or
        // its (clearly stale) activeIndex of 5.
        assertEquals(disk, restore.urls)
        assertEquals(0, restore.activeIndex)
    }

    @Test
    fun `a shortcut URL during a restore loads into the restored active tab, not a new tab`() {
        val disk = listOf("https://a.example.com", "https://b.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = disk, diskActive = 0,
            shortcutUrl = "https://shortcut.example.com", externalUrl = null
        )
        assertTrue(plan is LaunchPlan.Restore)
        assertEquals(
            PostRestoreAction.LoadShortcut("https://shortcut.example.com"),
            (plan as LaunchPlan.Restore).postAction
        )
    }

    @Test
    fun `an external URL during a restore opens as an additional tab, preserving the session`() {
        val disk = listOf("https://a.example.com", "https://b.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = disk, diskActive = 0,
            shortcutUrl = null, externalUrl = "https://shared-link.example.com"
        )
        assertTrue(plan is LaunchPlan.Restore)
        assertEquals(
            PostRestoreAction.OpenExternalTab("https://shared-link.example.com"),
            (plan as LaunchPlan.Restore).postAction
        )
    }

    @Test
    fun `shortcut still takes precedence over external URL during a restore`() {
        val disk = listOf("https://a.example.com")
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = disk, diskActive = 0,
            shortcutUrl = "https://shortcut.example.com",
            externalUrl = "https://shared-link.example.com"
        )
        assertTrue(plan is LaunchPlan.Restore)
        assertEquals(
            PostRestoreAction.LoadShortcut("https://shortcut.example.com"),
            (plan as LaunchPlan.Restore).postAction
        )
    }

    @Test
    fun `a null disk session with nothing saved falls through to CreateSingle`() {
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = null, diskActive = 0,
            shortcutUrl = null, externalUrl = null
        )
        assertTrue(plan is LaunchPlan.CreateSingle)
    }

    @Test
    fun `an on-disk session with an empty url list is still treated as a restore`() {
        // A Session object can exist on disk with an empty urls list
        // (e.g. every tab was a non-restorable scheme). This must still
        // be routed to Restore -- TabManager.restore() itself falls
        // back to opening one home tab for an empty list, so the
        // decision here should not duplicate that fallback.
        val plan = resolveLaunchPlan(
            savedUrls = null, savedActive = 0, savedJs = null,
            diskUrls = emptyList(), diskActive = 0,
            shortcutUrl = null, externalUrl = null
        )
        assertTrue(plan is LaunchPlan.Restore)
        assertEquals(emptyList<String>(), (plan as LaunchPlan.Restore).urls)
    }
}
