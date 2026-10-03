package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/** Immutable copy of user prefs, safe to read from any thread without IO. */
data class PrefsSnapshot(
    val homepage: String,
    val javaScriptEnabled: Boolean
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

    fun snapshot(): PrefsSnapshot = PrefsSnapshot(
        homepage = homepage,
        javaScriptEnabled = javaScriptEnabled
    )

    companion object {
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_JS       = "javascript_enabled"
        const val DEFAULT_HOMEPAGE     = "https://duckduckgo.com/"

        @Volatile private var inst: Prefs? = null

        fun get(context: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(context.applicationContext).also { inst = it }
            }
    }
}
