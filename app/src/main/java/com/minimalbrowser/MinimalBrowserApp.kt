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
 * Boot-time warm-up, WebView process setup, and warehouse seeding.
 *
 * 1. setDataDirectorySuffix() is called FIRST, before anything else
 *    touches WebView. On some OEM ROMs (notably Xiaomi HyperOS) the
 *    WebView silently falls back to single-process mode if the data
 *    directory suffix is not set consistently -- and in that mode a
 *    GPU driver crash in the renderer takes down the whole app.
 *
 * 2. warmUpWebView() spins up Chromium's renderer process and loads
 *    its native libraries before MainActivity's real WebView is asked
 *    to display anything. Cold start feels noticeably snappier.
 *
 * 3. AdBlocker.get() kicks off host-list loading on a Dispatchers.IO
 *    coroutine. By the time the first navigation fires
 *    shouldInterceptRequest, the HostSet is usually built.
 *
 * 4. BlocklistRefreshWorker.schedule() installs the periodic 12h job
 *    that refreshes BOTH warehouses (hosts and cosmetics).
 *
 * 5. Cosmetic warehouse bootstrap: seed defaults on first boot, then
 *    parse the raw EasyList text into a CosmeticRules object and hand
 *    it to CosmeticFilter. Runs on IO; the filter uses its built-in
 *    curated selectors until this completes, so a slow network does
 *    not delay the first page render.
 */
class MinimalBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must be before ANY WebView is created.
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

        bootstrapCosmetics()
    }

    /**
     * Seed the cosmetic warehouse on first boot, then load whatever
     * rules are on disk into CosmeticFilter. Runs entirely on IO.
     */
    private fun bootstrapCosmetics() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = CosmeticStore.get(this@MinimalBrowserApp)
                if (store.listAll().isEmpty()) {
                    val defaults = Prefs.DEFAULT_COSMETIC_URLS
                        .lineSequence()
                        .map { it.trim() }
                        .filter {
                            it.isNotEmpty() &&
                            !it.startsWith("#") &&
                            !it.startsWith("!")
                        }
                        .toList()
                    Log.i(TAG, "Seeding " + defaults.size + " cosmetic list(s)")
                    for (u in defaults) store.addAndFetch(u)
                }
                val raw = store.loadAllRaw()
                if (raw.isNotEmpty()) {
                    CosmeticFilter.updateFrom(raw)
                } else {
                    Log.i(TAG, "Cosmetic warehouse empty -- using fallback selectors")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Cosmetic bootstrap failed: " + t.message)
            }
        }
    }

    /**
     * Best-effort WebView warm-up.
     *
     * The WebView is created and immediately asked to load about:blank,
     * which forces Chromium to fork its renderer process. We destroy it
     * ~8 seconds later so the throwaway does not sit in memory forever.
     * If the user is slower than that to reach the app, they lose the
     * speedup but nothing breaks.
     */
    private fun warmUpWebView() {
        try {
            val throwaway = WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true  // never hit the network
                loadUrl("about:blank")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try { throwaway.destroy() } catch (_: Throwable) { /* ignore */ }
            }, 8_000L)
        } catch (t: Throwable) {
            // Never let warm-up failure take down app boot.
            Log.w(TAG, "WebView warm-up failed: " + t.message)
        }
    }

    private companion object {
        const val TAG = "MinimalBrowserApp"
    }
}
