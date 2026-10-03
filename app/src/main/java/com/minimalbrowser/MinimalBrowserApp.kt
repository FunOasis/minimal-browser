package com.minimalbrowser

import android.app.Application
import android.util.Log

/**
 * Boot-time warm-up.
 *
 * AdBlocker.get() is idempotent and kicks off asset parsing on a
 * Dispatchers.IO coroutine internally. Calling it here — before
 * MainActivity.onCreate — means the trie is usually built by the time
 * the user's first navigation fires shouldInterceptRequest. No disk IO
 * ever lands on the intercept path.
 */
class MinimalBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val blocker = AdBlocker.get(this)
        Log.i("MinimalBrowserApp", "AdBlocker warm-up: ${blocker.stats()}")
    }
}
