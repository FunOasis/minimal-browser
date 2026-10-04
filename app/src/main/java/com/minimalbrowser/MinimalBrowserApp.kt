package com.minimalbrowser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Boot-time warm-up and WebView process setup.
 *
 * 1. setDataDirectorySuffix() is called FIRST, before anything else
 *    touches WebView. On several OEM ROMs (notably Xiaomi HyperOS) the
 *    WebView will silently fall back to single-process mode if the data
 *    directory suffix isn't set consistently — and in single-process
 *    mode a GPU driver crash in the renderer takes down the whole app.
 *    With the suffix set, WebView runs out-of-process and a renderer
 *    crash is recoverable via WebViewClient.onRenderProcessGone().
 *
 * 2. warmUpWebView() creates a throwaway WebView on the main thread and
 *    immediately loads about:blank. This spins up Chromium's renderer
 *    process, loads its native libraries, and primes its JNI bridge —
 *    all *before* MainActivity's real WebView is asked to display
 *    anything. Net effect: cold start feels noticeably snappier because
 *    the framework has a head start on the expensive part.
 *
 * 3. AdBlocker.get() is idempotent and kicks off asset parsing on a
 *    Dispatchers.IO coroutine internally. Calling it here — before
 *    MainActivity.onCreate — means the trie is usually built by the
 *    time the user's first navigation fires shouldInterceptRequest.
 *    No disk IO ever lands on the intercept path.
 */
class MinimalBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must be before ANY WebView is created.
        try {
            WebView.setDataDirectorySuffix("mb_webview")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setDataDirectorySuffix: ${e.message}")
        }

        // Pre-warm Chromium. Safe to delete if it misbehaves on some ROM.
        warmUpWebView()

        val blocker = AdBlocker.get(this)
        Log.i(TAG, "AdBlocker warm-up: ${blocker.stats()}")
    }

    /**
     * Best-effort WebView warm-up.
     *
     * The WebView is created and immediately asked to load about:blank
     * — which forces Chromium to fork its renderer process. We then
     * destroy it ~8 seconds later so the throwaway doesn't sit in
     * memory forever.
     *
     * 8 seconds is enough for MainActivity to have spun up its own
     * WebView under normal conditions; if the user is slower than that,
     * they lose the speedup but nothing breaks.
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
            Log.w(TAG, "WebView warm-up failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "MinimalBrowserApp"
    }
}
