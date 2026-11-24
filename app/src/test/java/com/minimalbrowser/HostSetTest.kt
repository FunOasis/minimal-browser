package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers HostSet.matches() -- the allocation-free rewrite of the
 * ad-block host lookup (see HostSet.kt's class doc for why it was
 * rewritten). Pure Kotlin, no Android dependency.
 */
class HostSetTest {

    @Test
    fun `exact single-label host matches itself`() {
        val set = HostSet.from(listOf("localhost"))
        assertTrue(set.matches("localhost"))
        assertFalse(set.matches("notlocalhost"))
    }

    @Test
    fun `exact multi-label host matches itself`() {
        val set = HostSet.from(listOf("ads.example.com"))
        assertTrue(set.matches("ads.example.com"))
    }

    @Test
    fun `subdomain of a blocked host is blocked`() {
        val set = HostSet.from(listOf("ads.example.com"))
        // x.ads.example.com has "ads.example.com" as a dot-boundary suffix.
        assertTrue(set.matches("x.ads.example.com"))
        assertTrue(set.matches("tracker.ads.example.com"))
    }

    @Test
    fun `parent domain of a blocked host is NOT blocked`() {
        val set = HostSet.from(listOf("ads.example.com"))
        assertFalse(set.matches("example.com"))
        assertFalse(set.matches("com"))
    }

    @Test
    fun `sibling subdomain is not blocked`() {
        val set = HostSet.from(listOf("ads.example.com"))
        assertFalse(set.matches("notads.example.com"))
        assertFalse(set.matches("other.example.com"))
    }

    @Test
    fun `blocking a bare TLD-plus-one blocks every subdomain`() {
        val set = HostSet.from(listOf("example.com"))
        assertTrue(set.matches("example.com"))
        assertTrue(set.matches("www.example.com"))
        assertTrue(set.matches("a.b.c.example.com"))
        assertFalse(set.matches("notexample.com"))
        assertFalse(set.matches("example.org"))
    }

    @Test
    fun `unrelated host does not match`() {
        val set = HostSet.from(listOf("ads.example.com", "tracker.io"))
        assertFalse(set.matches("unrelated.net"))
    }

    @Test
    fun `empty set matches nothing`() {
        assertFalse(HostSet.EMPTY.matches("anything.com"))
        assertFalse(HostSet.EMPTY.matches(""))
    }

    @Test
    fun `empty host matches nothing`() {
        val set = HostSet.from(listOf("example.com"))
        assertFalse(set.matches(""))
    }

    @Test
    fun `duplicate hosts collapse and size reflects unique count`() {
        val set = HostSet.from(listOf("a.com", "a.com", "a.com", "b.com"))
        assertEquals(2, set.size)
        assertTrue(set.matches("a.com"))
        assertTrue(set.matches("b.com"))
    }

    @Test
    fun `deep subdomain chain matches a mid-chain blocked entry`() {
        val set = HostSet.from(listOf("tracking.cdn.example.com"))
        assertTrue(set.matches("a.b.tracking.cdn.example.com"))
        assertFalse(set.matches("cdn.example.com"))
        assertFalse(set.matches("notracking.cdn.example.com"))
    }

    @Test
    fun `many hosts all resolve correctly via binary search`() {
        val hosts = (1..2000).map { "host$it.example.com" }
        val set = HostSet.from(hosts)
        assertEquals(2000, set.size)
        for (i in listOf(1, 500, 1000, 1999, 2000)) {
            assertTrue("host$i.example.com should match", set.matches("host$i.example.com"))
            assertTrue(
                "sub.host$i.example.com should match",
                set.matches("sub.host$i.example.com")
            )
        }
        assertFalse(set.matches("host2001.example.com"))
    }

    @Test
    fun `malformed host with consecutive dots does not crash`() {
        val set = HostSet.from(listOf("example.com"))
        // Must not throw; result does not need to be a particular value.
        set.matches("a..b.com")
        set.matches(".")
        set.matches("..")
        set.matches("a.")
        set.matches(".a")
    }
}
