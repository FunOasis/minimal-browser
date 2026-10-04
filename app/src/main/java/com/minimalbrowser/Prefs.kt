package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/** Immutable copy of user prefs, safe to read from any thread without IO. */
data class PrefsSnapshot(
    val homepage: String,
    val javaScriptEnabled: Boolean,
    val customBlocklist: String,
    val customFilters: String
)

class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_prefs", Context.MODE_PRIVATE)

    var homepage: String
        get() = sp.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
        set(v) = sp.edit().putString(KEY_HOMEPAGE, v).apply()

    var javaScriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JS, true)
        set(v) = sp.edit().putBoolean(KEY_JS, v).apply()

    /**
     * User-pasted custom host rules (one per line). Merged on top of the
     * built-in assets/blocklist.txt rules by AdBlocker at boot and on
     * every save from the Custom filters dialog.
     */
    var customBlocklist: String
        get() = sp.getString(KEY_CUSTOM_BLOCKLIST, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_BLOCKLIST, v).apply()

    /**
     * User-pasted custom URL keyword patterns (one per line). Merged on
     * top of the built-in assets/filters.txt rules by AdBlocker.
     */
    var customFilters: String
        get() = sp.getString(KEY_CUSTOM_FILTERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_FILTERS, v).apply()

    fun snapshot(): PrefsSnapshot = PrefsSnapshot(
        homepage = homepage,
        javaScriptEnabled = javaScriptEnabled,
        customBlocklist = customBlocklist,
        customFilters = customFilters
    )

    companion object {
        private const val KEY_HOMEPAGE          = "homepage"
        private const val KEY_JS                = "javascript_enabled"
        private const val KEY_CUSTOM_BLOCKLIST  = "custom_blocklist"
        private const val KEY_CUSTOM_FILTERS    = "custom_filters"

        /**
         * Internal sentinel for the built-in home page. BlockingWebViewClient
         * intercepts main-frame requests to this URL and serves
         * assets/home.html. Never sent to the network.
         */
        const val HOME_URL         = "minimal://home"
        const val DEFAULT_HOMEPAGE = HOME_URL

        @Volatile private var inst: Prefs? = null

        fun get(context: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(context.applicationContext).also { inst = it }
            }
    }
}
