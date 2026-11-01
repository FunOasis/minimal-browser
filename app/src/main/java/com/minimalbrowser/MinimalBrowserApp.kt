package com.minimalbrowser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MinimalBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            WebView.setDataDirectorySuffix("mb_webview")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setDataDirectorySuffix: " + e.message)
        }

        warmUpWebView()

        val blocker = AdBlocker.get(this)
        Log.i(TAG, "AdBlocker warm-up: " + blocker.stats().toString())

        try {
            BlocklistRefreshWorker.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager schedule failed: " + t.message)
        }

        runOneTimeCleanupThenLoadCosmetics()
    }

    /**
     * Runs the legacy-cleanup migration (once) and then loads whatever
     * cosmetic rules remain on disk. Doing both in a single coroutine
     * guarantees the load sees the post-cleanup state, eliminating the
     * race where stale rules get populated before cleanup finishes.
     */
    private fun runOneTimeCleanupThenLoadCosmetics() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = Prefs.get(this@MinimalBrowserApp)
                if (!p.legacyDefaultsCleaned) {
                    val store = CosmeticStore.get(this@MinimalBrowserApp)
                    val entries = store.listAll()
                    for (e in entries) store.remove(e.url)
                    p.legacyDefaultsCleaned = true
                    Log.i(TAG, "Legacy cosmetic warehouse cleared (" +
                        entries.size + " entries removed)")
                }

                val raw = CosmeticStore.get(this@MinimalBrowserApp).loadAllRaw()
                if (raw.isNotEmpty()) {
                    CosmeticFilter.updateFrom(raw)
                    Log.i(TAG, "Cosmetic rules loaded from warehouse")
                } else {
                    CosmeticFilter.clear()
                    Log.i(TAG, "Cosmetic warehouse empty")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "boot cosmetic init failed: " + t.message)
            }
        }
    }

    private fun warmUpWebView() {
        try {
            val throwaway = WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                loadUrl("about:blank")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try { throwaway.destroy() } catch (_: Throwable) { /* ignore */ }
            }, 8_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "WebView warm-up failed: " + t.message)
        }
    }

    companion object {
        private const val TAG = "MinimalBrowserApp"

        /**
         * Persist the CookieManager's in-memory cookie buffer to disk.
         *
         * Set-Cookie responses and document.cookie writes are buffered
         * in RAM; they only reach storage on WebView's internal periodic
         * flush, or on the explicit flush in MainActivity.onPause(). A
         * tab whose WebView is destroyed -- closed by the user, frozen
         * for idleness, evicted at the 10-tab cap, torn down with the
         * activity, or killed by a renderer crash -- can be holding the
         * newest cookie updates for its site (fresh session tokens,
         * remember-me flags). If the process then dies before the next
         * onPause flush (low memory, crash, user swipe-away), those
         * updates vanish and the user is silently logged out.
         *
         * TabManager calls this from every WebView-destroy path.
         *
         * Wrapped in try/catch because CookieManager can throw in odd
         * process states (before the WebView provider initialises, or
         * after its death); a lost flush is never fatal.
         */
        @JvmStatic
        fun flushCookies() {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
        }
    }
}
