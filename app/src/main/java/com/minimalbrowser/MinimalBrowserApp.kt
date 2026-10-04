package com.minimalbrowser

import android.app.Application
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
 * 2. AdBlocker.get() is idempotent and kicks off asset parsing on a
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
            // Thrown if WebView was already initialized elsewhere in the
            // process. Harmless — just means the suffix is already set.
            Log.w(TAG, "setDataDirectorySuffix: ${e.message}")
        }

        val blocker = AdBlocker.get(this)
        Log.i(TAG, "AdBlocker warm-up: ${blocker.stats()}")
    }

    private companion object {
        const val TAG = "MinimalBrowserApp"
    }
}
