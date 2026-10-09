package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * User-editable preferences. The warehouse owns subscription lists, so
 * this class is intentionally small: homepage, the global JavaScript
 * default, the two free-form filter text fields, and the per-site
 * blocking whitelist.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_prefs", Context.MODE_PRIVATE)

    var homepage: String
        get() = sp.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(v) = sp.edit().putString(KEY_HOMEPAGE, v).apply()

    var javaScriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JS, true)
        set(v) = sp.edit().putBoolean(KEY_JS, v).apply()

    var customBlocklist: String
        get() = sp.getString(KEY_CUSTOM_BLOCKLIST, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_BLOCKLIST, v).apply()

    var customFilters: String
        get() = sp.getString(KEY_CUSTOM_FILTERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_FILTERS, v).apply()

    /**
     * Hosts (and their subdomains) on which ad blocking is disabled.
     * Stored as a newline-separated blob. A disabled host applies to
     * itself and any subdomain, matching how users think of "this site".
     */
    var disabledHosts: Set<String>
        get() {
            val blob = sp.getString(KEY_DISABLED_HOSTS, "") ?: return emptySet()
            if (blob.isBlank()) return emptySet()
            return blob.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        set(v) {
            val cleaned = v.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSortedSet()
            sp.edit().putString(KEY_DISABLED_HOSTS, cleaned.joinToString("\n")).apply()
        }

    /**
     * True if the given host, or any parent domain of it, has blocking
     * disabled. Called from AdBlocker.isBlocked() and the cosmetic
     * filter gate. Suffix matching is on dot boundaries, so a user who
     * disabled "example.com" also disables "ads.example.com", but a
     * user who disabled "ads.example.com" leaves "example.com" alone.
     */
    fun isHostDisabled(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return false
        val set = disabledHosts
        if (set.isEmpty()) return false
        var cur = h
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (cur.isEmpty()) break
        }
        return false
    }

    fun addDisabledHost(host: String) {
        val h = host.lowercase().removePrefix("www.").trim()
        if (h.isEmpty()) return
        disabledHosts = disabledHosts + h
    }

    /**
     * Remove any disabled-host entry that would currently whitelist the
     * given host. "Remove entry for foo.example.com" also clears a
     * pre-existing "example.com" entry if that is what was matching.
     */
    fun removeDisabledHostsMatching(host: String) {
        val h = host.lowercase().removePrefix("www.").trim()
        if (h.isEmpty()) return
        disabledHosts = disabledHosts.filterNot { d ->
            h == d || h.endsWith("." + d)
        }.toSet()
    }

    companion object {
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_JS = "javascript_enabled"
        private const val KEY_CUSTOM_BLOCKLIST = "custom_blocklist"
        private const val KEY_CUSTOM_FILTERS = "custom_filters"
        private const val KEY_DISABLED_HOSTS = "disabled_hosts"

        const val HOME_URL = "minimal://home"
        const val DEFAULT_HOMEPAGE = HOME_URL

        const val DEFAULT_SUBSCRIPTION_URLS =
            "https://blocklistproject.github.io/Lists/ads.txt\n" +
            "https://blocklistproject.github.io/Lists/tracking.txt"

        const val DEFAULT_COSMETIC_URLS =
            "https://easylist.to/easylist/easylist.txt\n" +
            "https://easylist.to/easylist/easyprivacy.txt"

        @Volatile private var inst: Prefs? = null

        fun get(context: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(context.applicationContext).also { inst = it }
            }
    }
}
