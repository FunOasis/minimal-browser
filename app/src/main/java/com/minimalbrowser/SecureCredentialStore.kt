package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences
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
 *
 * Hosts are normalised: trimmed, lowercased, "www." stripped. Lookups
 * try both the bare host and the "www." variant so a credential saved
 * on "example.com" is found when visiting "www.example.com" and vice
 * versa.
 */
class SecureCredentialStore private constructor(context: Context) {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "minimal_secure_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Throwable) {
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

    private fun normalizeHost(host: String): String =
        host.trim().lowercase().removePrefix("www.")

    private fun key(host: String, user: String, field: String): String =
        "$host|$user|$field"

    private fun primaryKey(host: String): String = "$host|primary"

    companion object {
        @Volatile private var inst: SecureCredentialStore? = null
        fun get(context: Context): SecureCredentialStore =
            inst ?: synchronized(this) {
                inst ?: SecureCredentialStore(context.applicationContext).also { inst = it }
            }
    }
}
