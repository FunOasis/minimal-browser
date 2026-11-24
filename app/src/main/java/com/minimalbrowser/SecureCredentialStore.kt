package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted credential store.
 *
 * Backed by EncryptedSharedPreferences (AES256-SIV for keys, AES256-GCM
 * for values). The master key lives in Android Keystore and never leaves
 * the device.
 *
 * Key layout inside the encrypted prefs:
 *   "host|username|user"   -> username
 *   "host|username|pass"   -> password
 *   "host|primary"         -> most-recently-saved username for host
 *   "httpauth|host|realm|user" / "...|pass" -> HTTP Basic/Digest auth,
 *       kept in its own namespace (see below) so it never shows up
 *       alongside page-login credentials in VaultActivity.
 *
 * Hosts are normalised: trimmed, lowercased, "www." stripped. Lookups
 * try both the bare host and the "www." variant so a credential saved
 * on "example.com" is found when visiting "www.example.com" and vice
 * versa.
 *
 * Degraded mode
 * -------------
 * EncryptedSharedPreferences.create() can fail -- most commonly after
 * the app's data is restored onto a different device (the Keystore-
 * backed master key does not travel with a backup) or after certain OS
 * upgrades disturb Keystore state. An earlier version of this class
 * swallowed that failure into a null `prefs` and silently behaved as if
 * the vault were simply empty forever after: no saved password was
 * ever offered, no new one was ever stored, and nothing told the user
 * why.
 *
 * This version:
 *   1. Exposes [isAvailable] and [degradedReason] so callers (see
 *      VaultActivity, VaultPillController) can tell the user plainly
 *      instead of pretending the vault is just empty.
 *   2. Makes one automatic recovery attempt: if creation fails, the
 *      orphaned encrypted-prefs file and Keystore master-key entry are
 *      deleted and creation is retried once. This is the same recovery
 *      Google's own EncryptedSharedPreferences guidance recommends for
 *      a corrupted/orphaned master key; it cannot recover data
 *      encrypted under a key that no longer exists, but it does let the
 *      vault start working again for anything saved from that point on,
 *      rather than staying permanently dead for the life of the install.
 */
class SecureCredentialStore private constructor(context: Context) {

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val PREFS_FILE_NAME = "minimal_secure_vault"

        @Volatile private var inst: SecureCredentialStore? = null
        fun get(context: Context): SecureCredentialStore =
            inst ?: synchronized(this) {
                inst ?: SecureCredentialStore(context.applicationContext).also { inst = it }
            }

        // Pure key-formation / host-normalisation helpers, kept as
        // companion functions (rather than instance methods) specifically
        // so they can be unit tested without constructing a real
        // EncryptedSharedPreferences-backed instance -- see
        // SecureCredentialStoreKeyTest.kt.
        internal fun normalizeHost(host: String): String =
            host.trim().lowercase().removePrefix("www.")

        internal fun key(host: String, user: String, field: String): String =
            "$host|$user|$field"

        internal fun primaryKey(host: String): String = "$host|primary"

        /**
         * Key for an HTTP Basic/Digest auth credential. Namespaced under
         * "httpauth|" so it can never collide with (or be mistaken for)
         * a page-login key for the same host, even if a page's login
         * form username happened to be the literal string "primary" or
         * similar -- and so VaultActivity's listing, which only reads
         * the plain host/user keys, never shows an HTTP-auth credential
         * mixed in with saved page passwords.
         */
        internal fun httpAuthKey(host: String, realm: String, field: String): String =
            "httpauth|" + normalizeHost(host) + "|" + realm + "|" + field
    }

    /** Null until an init failure happens; a short machine-readable tag otherwise. */
    @Volatile var degradedReason: String? = null
        private set

    private val prefs: SharedPreferences? = openOrRecover(context)

    /** False if the encrypted vault could not be initialized (see class doc). */
    val isAvailable: Boolean get() = prefs != null

    private fun openOrRecover(context: Context): SharedPreferences? {
        createEncryptedPrefs(context)?.let { return it }

        Log.w(TAG, "EncryptedSharedPreferences init failed; attempting one-time reset")
        val recovered = try {
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Keystore cleanup during recovery failed: " + t.message)
            }
            createEncryptedPrefs(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Vault recovery attempt failed: " + t.message)
            null
        }

        if (recovered == null) {
            degradedReason = "init_failed"
            Log.e(
                TAG,
                "Password vault unavailable: initialization failed twice. Saved " +
                    "passwords will not be offered or stored until this is resolved."
            )
        } else {
            degradedReason = "recovered"
            Log.w(
                TAG,
                "Password vault reset and recovered; any previously saved " +
                    "passwords were lost and must be re-saved."
            )
        }
        return recovered
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        Log.w(TAG, "createEncryptedPrefs failed: " + t.javaClass.simpleName + ": " + t.message)
        null
    }

    data class Credential(
        val host: String,
        val username: String,
        val password: String
    )

    fun save(host: String, username: String, password: String) {
        val p = prefs ?: return
        if (host.isBlank() || username.isBlank()) return
        val h = normalizeHost(host)
        val u = username.trim()
        p.edit()
            .putString(key(h, u, "user"), u)
            .putString(key(h, u, "pass"), password)
            .putString(primaryKey(h), u)
            .apply()
    }

    fun accountsForHost(host: String): List<Credential> {
        val p = prefs ?: return emptyList()
        val h = normalizeHost(host)
        val variants = listOf(h, if (h.startsWith("www.")) h.substring(4) else "www.$h")
        val seen = HashSet<String>()
        val out = ArrayList<Credential>()
        for (variant in variants) {
            for ((k, v) in p.all) {
                if (!k.startsWith("$variant|") || !k.endsWith("|user")) continue
                val user = v as? String ?: continue
                if (user.isBlank() || !seen.add("$variant|$user")) continue
                val pass = p.getString(key(variant, user, "pass"), "") ?: ""
                out.add(Credential(variant, user, pass))
            }
        }
        return out
    }

    fun allHosts(): List<String> {
        val p = prefs ?: return emptyList()
        val set = LinkedHashSet<String>()
        for (k in p.all.keys) {
            if (k.endsWith("|primary")) set.add(k.removeSuffix("|primary"))
        }
        return set.toList().sorted()
    }

    fun remove(host: String, username: String) {
        val p = prefs ?: return
        val h = normalizeHost(host)
        val u = username.trim()
        p.edit()
            .remove(key(h, u, "user"))
            .remove(key(h, u, "pass"))
            .apply()
        if (p.getString(primaryKey(h), null) == u) {
            val remaining = accountsForHost(h)
            if (remaining.isEmpty()) p.edit().remove(primaryKey(h)).apply()
            else p.edit().putString(primaryKey(h), remaining.first().username).apply()
        }
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    // ---------------------------------------------------------------------
    // HTTP Basic/Digest auth credentials.
    //
    // Replaces android.webkit.WebViewDatabase.setHttpAuthUsernamePassword /
    // getHttpAuthUsernamePassword, which is both deprecated and, unlike
    // this class, not encrypted at rest. Kept under its own "httpauth|"
    // key prefix so it is addressed separately from page-login
    // credentials above and never appears mixed into VaultActivity's
    // "saved passwords" listing, which only reads the plain host/user
    // keys.
    // ---------------------------------------------------------------------

    fun saveHttpAuth(host: String, realm: String, username: String, password: String) {
        val p = prefs ?: return
        if (host.isBlank()) return
        p.edit()
            .putString(httpAuthKey(host, realm, "user"), username)
            .putString(httpAuthKey(host, realm, "pass"), password)
            .apply()
    }

    fun httpAuthFor(host: String, realm: String): Credential? {
        val p = prefs ?: return null
        val user = p.getString(httpAuthKey(host, realm, "user"), null) ?: return null
        val pass = p.getString(httpAuthKey(host, realm, "pass"), "") ?: ""
        return Credential(normalizeHost(host), user, pass)
    }
}
