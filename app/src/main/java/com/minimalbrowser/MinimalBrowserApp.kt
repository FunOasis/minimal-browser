package com.minimalbrowser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot-time warm-up, WebView process setup, and one-time migration.
 *
 * No hardcoded ad-blocking data is loaded. On a fresh install the
 * blocklist and cosmetic warehouses are empty, and the ad blocker is
 * inert until the user supplies subscription URLs.
 *
 * One migration runs on the first launch after upgrading from a build
 * that shipped default EasyList cosmetic URLs: the cosmetic warehouse
 * is wiped, and a flag is set so it never runs again.
 */
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

        runOneTimeCleanup()
        loadCosmeticsIfPresent()
    }

    /**
     * Runs once per install on the first launch after this build is
     * applied. Wipes the cosmetic warehouse that older builds seeded
     * from hardcoded EasyList URLs. After this, the cosmetic warehouse
     * is empty until a future UI lets the user add cosmetic lists.
     *
     * The flag is checked and set synchronously here, on the
     * Application's main thread, before any coroutine touches the
     * warehouse. This avoids a race where the load coroutine reads the
     * warehouse while the cleanup is still deleting entries.
     */
    private fun runOneTimeCleanup() {
        val p = Prefs.get(this)
        if (p.legacyDefaultsCleaned) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = CosmeticStore.get(this@MinimalBrowserApp)
                val entries = store.listAll()
                for (e in entries) {
                    store.remove(e.url)
                }
                p.legacyDefaultsCleaned = true
                Log.i(TAG, "Legacy cosmetic warehouse cleared (" +
                    entries.size + " entries removed)")
            } catch (t: Throwable) {
                Log.w(TAG, "Cleanup failed: " + t.message)
            }
        }
    }

    /**
     * Loads whatever cosmetic rules are on disk. On a fresh install
     * this finds nothing and CosmeticFilter stays on its built-in
     * fallback selector list.
     */
    private fun loadCosmeticsIfPresent() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = CosmeticStore.get(this@MinimalBrowserApp)
                val raw = store.loadAllRaw()
                if (raw.isNotEmpty()) {
                    CosmeticFilter.updateFrom(raw)
                    Log.i(TAG, "Cosmetic rules loaded from warehouse")
                } else {
                    Log.i(TAG, "Cosmetic warehouse empty -- using fallback selectors")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Cosmetic load failed: " + t.message)
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
