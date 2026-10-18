package com.minimalbrowser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private companion object {
        const val TAG = "MinimalBrowserApp"
    }
}
