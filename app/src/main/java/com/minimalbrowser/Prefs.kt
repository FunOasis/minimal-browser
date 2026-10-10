package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * User-editable preferences.
 *
 * Deliberately contains no default subscription URLs. Ad blocking is
 * entirely opt-in: the user pastes list URLs into the ad-blocking
 * dialog, or the warehouse stays empty. Nothing is shipped in the APK.
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
     * Hosts (and subdomains) on which ad blocking is disabled.
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
     * Hosts (and subdomains) that should load with a desktop UA.
     */
    var desktopHosts: Set<String>
        get() {
            val blob = sp.getString(KEY_DESKTOP_HOSTS, "") ?: return emptySet()
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
            sp.edit().putString(KEY_DESKTOP_HOSTS, cleaned.joinToString("\n")).apply()
        }

    /**
     * Tracking parameters to strip from main-frame navigations.
     */
    var removeParams: Set<String>
        get() {
            if (!sp.contains(KEY_REMOVE_PARAMS)) return UrlCleaner.DEFAULT_REMOVE_PARAMS
            val blob = sp.getString(KEY_REMOVE_PARAMS, "") ?: return UrlCleaner.DEFAULT_REMOVE_PARAMS
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
            sp.edit().putString(KEY_REMOVE_PARAMS, cleaned.joinToString("\n")).apply()
        }

    /**
     * One-shot flag for the upgrade from the old build that shipped
     * hardcoded EasyList URLs in the cosmetic warehouse. When false,
     * MinimalBrowserApp wipes the cosmetic warehouse once and flips
     * this to true. Never re-arms.
     */
    var legacyDefaultsCleaned: Boolean
        get() = sp.getBoolean(KEY_LEGACY_CLEANED, false)
        set(v) = sp.edit().putBoolean(KEY_LEGACY_CLEANED, v).apply()

    // ------------------------------------------------------------------
    // Host-list helpers
    // ------------------------------------------------------------------

    fun isHostDisabled(host: String): Boolean = hostInList(host, disabledHosts)

    fun addDisabledHost(host: String) {
        val h = normalize(host)
        if (h.isNotEmpty()) disabledHosts = disabledHosts + h
    }

    fun removeDisabledHostsMatching(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        disabledHosts = disabledHosts.filterNot { d ->
            h == d || h.endsWith("." + d)
        }.toSet()
    }

    fun isDesktopHost(host: String): Boolean = hostInList(host, desktopHosts)

    fun addDesktopHost(host: String) {
        val h = normalize(host)
        if (h.isNotEmpty()) desktopHosts = desktopHosts + h
    }

    fun removeDesktopHostMatching(host: String) {
        val h = normalize(host)
        if (h.isEmpty()) return
        desktopHosts = desktopHosts.filterNot { d ->
            h == d || h.endsWith("." + d)
        }.toSet()
    }

    private fun hostInList(host: String, list: Set<String>): Boolean {
        val h = normalize(host)
        if (h.isEmpty() || list.isEmpty()) return false
        var cur = h
        while (true) {
            if (cur in list) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (cur.isEmpty()) break
        }
        return false
    }

    private fun normalize(host: String): String =
        host.lowercase().removePrefix("www.").trim()

    companion object {
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_JS = "javascript_enabled"
        private const val KEY_CUSTOM_BLOCKLIST = "custom_blocklist"
        private const val KEY_CUSTOM_FILTERS = "custom_filters"
        private const val KEY_DISABLED_HOSTS = "disabled_hosts"
        private const val KEY_DESKTOP_HOSTS = "desktop_hosts"
        private const val KEY_REMOVE_PARAMS = "remove_params"
        private const val KEY_LEGACY_CLEANED = "legacy_defaults_cleaned"

        const val HOME_URL = "minimal://home"
        const val DEFAULT_HOMEPAGE = HOME_URL

        @Volatile private var inst: Prefs? = null

        fun get(context: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(context.applicationContext).also { inst = it }
            }
    }
}
