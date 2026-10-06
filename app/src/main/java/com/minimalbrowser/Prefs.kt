package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * User-editable preferences. The warehouse owns everything related to
 * subscription lists, so this class is intentionally small: homepage,
 * JavaScript toggle, and the two free-form filter text fields the user
 * can edit directly.
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

    companion object {
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_JS = "javascript_enabled"
        private const val KEY_CUSTOM_BLOCKLIST = "custom_blocklist"
        private const val KEY_CUSTOM_FILTERS = "custom_filters"

        const val HOME_URL = "minimal://home"
        const val DEFAULT_HOMEPAGE = HOME_URL

        const val DEFAULT_SUBSCRIPTION_URLS =
            "https://blocklistproject.github.io/Lists/ads.txt\n" +
            "https://blocklistproject.github.io/Lists/tracking.txt"

        /**
         * Cosmetic-rule sources seeded on first boot of the cosmetic
         * warehouse. Both are full EasyList-format lists; the parser
         * picks up only the cosmetic (## / #@#) lines and ignores the
         * network-blocking rules, which are handled by AdBlocker
         * separately.
         */
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
