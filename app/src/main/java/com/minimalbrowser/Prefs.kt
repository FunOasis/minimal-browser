package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

data class PrefsSnapshot(
    val homepage: String,
    val javaScriptEnabled: Boolean,
    val customBlocklist: String,
    val customFilters: String,
    val subscriptionUrls: String,
    val cachedSubscriptionHosts: String
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

    var customBlocklist: String
        get() = sp.getString(KEY_CUSTOM_BLOCKLIST, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_BLOCKLIST, v).apply()

    var customFilters: String
        get() = sp.getString(KEY_CUSTOM_FILTERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_FILTERS, v).apply()

    var subscriptionUrls: String
        get() = sp.getString(KEY_SUBSCRIPTION_URLS, DEFAULT_SUBSCRIPTION_URLS)
            ?: DEFAULT_SUBSCRIPTION_URLS
        set(v) = sp.edit().putString(KEY_SUBSCRIPTION_URLS, v).apply()

    var cachedSubscriptionHosts: String
        get() = sp.getString(KEY_CACHED_SUB_HOSTS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CACHED_SUB_HOSTS, v).apply()

    var subscriptionRefreshTime: Long
        get() = sp.getLong(KEY_SUB_REFRESH_TIME, 0L)
        set(v) = sp.edit().putLong(KEY_SUB_REFRESH_TIME, v).apply()

    fun snapshot(): PrefsSnapshot = PrefsSnapshot(
        homepage = homepage,
        javaScriptEnabled = javaScriptEnabled,
        customBlocklist = customBlocklist,
        customFilters = customFilters,
        subscriptionUrls = subscriptionUrls,
        cachedSubscriptionHosts = cachedSubscriptionHosts
    )

    companion object {
        private const val KEY_HOMEPAGE          = "homepage"
        private const val KEY_JS                = "javascript_enabled"
        private const val KEY_CUSTOM_BLOCKLIST  = "custom_blocklist"
        private const val KEY_CUSTOM_FILTERS    = "custom_filters"
        private const val KEY_SUBSCRIPTION_URLS = "subscription_urls"
        private const val KEY_CACHED_SUB_HOSTS  = "cached_subscription_hosts"
        private const val KEY_SUB_REFRESH_TIME  = "subscription_refresh_time"

        const val HOME_URL         = "minimal://home"
        const val DEFAULT_HOMEPAGE = HOME_URL

        const val DEFAULT_SUBSCRIPTION_URLS =
            "https://blocklistproject.github.io/Lists/ads.txt\n" +
            "https://blocklistproject.github.io/Lists/tracking.txt"

        const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

        @Volatile private var inst: Prefs? = null

        fun get(context: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(context.applicationContext).also { inst = it }
            }
    }
}
