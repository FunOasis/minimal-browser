package com.minimalbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecureCredentialStore wraps EncryptedSharedPreferences, which needs a
 * real Android Keystore to exercise meaningfully -- not something this
 * patch could verify without a live build (see CHANGES.md). What IS
 * pure, Android-free logic in that class is its key-formation and
 * host-normalisation scheme, kept as companion functions specifically so
 * it can be tested here directly, with no Context and no
 * SecureCredentialStore instance required.
 *
 * These are exactly the invariants the degraded-mode and HTTP-auth-
 * migration fixes in this patch depend on:
 *   - a host and its "www." variant must normalise to the same key, so
 *     a credential saved on one is found on the other;
 *   - the new httpauth| namespace must never collide with the existing
 *     page-login key namespace, even for the same host/realm text,
 *     since VaultActivity's listing only reads the plain namespace and
 *     must never show an HTTP Basic/Digest credential mixed in.
 */
class SecureCredentialStoreKeyTest {

    @Test
    fun `host normalization strips www and lowercases`() {
        assertEquals("example.com", SecureCredentialStore.normalizeHost("WWW.Example.com"))
        assertEquals("example.com", SecureCredentialStore.normalizeHost("example.com"))
        assertEquals("example.com", SecureCredentialStore.normalizeHost("  example.com  "))
    }

    @Test
    fun `bare host and www variant normalize to the same key`() {
        assertEquals(
            SecureCredentialStore.normalizeHost("example.com"),
            SecureCredentialStore.normalizeHost("www.example.com")
        )
    }

    @Test
    fun `key format is stable and round-trips through its components`() {
        val k = SecureCredentialStore.key("example.com", "alice", "pass")
        assertEquals("example.com|alice|pass", k)
    }

    @Test
    fun `primary key is distinct from any user-field key for the same host`() {
        val primary = SecureCredentialStore.primaryKey("example.com")
        // "primary" as a USERNAME must not collide with the host's
        // primary-account marker key.
        val userKey = SecureCredentialStore.key("example.com", "primary", "user")
        assertNotEquals(primary, userKey)
    }

    @Test
    fun `http auth keys never collide with page-login keys for the same host and field`() {
        val pageLoginKey = SecureCredentialStore.key("example.com", "alice", "user")
        val httpAuth = SecureCredentialStore.httpAuthKey("example.com", "realm", "user")
        assertNotEquals(pageLoginKey, httpAuth)
        assertTrue(httpAuth.startsWith("httpauth|"))
    }

    @Test
    fun `http auth key normalizes the host the same way page-login keys do`() {
        val fromBare = SecureCredentialStore.httpAuthKey("example.com", "Login Realm", "user")
        val fromWww = SecureCredentialStore.httpAuthKey("www.example.com", "Login Realm", "user")
        assertEquals(fromBare, fromWww)
    }

    @Test
    fun `different realms on the same host produce different http auth keys`() {
        val a = SecureCredentialStore.httpAuthKey("router.local", "Admin Area", "user")
        val b = SecureCredentialStore.httpAuthKey("router.local", "Guest Area", "user")
        assertNotEquals(a, b)
    }
}
