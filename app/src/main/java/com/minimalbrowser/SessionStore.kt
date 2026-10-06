package com.minimalbrowser

import android.content.Context
import android.util.Log

/**
 * On-disk session snapshot.
 *
 * The Bundle-based restore only survives system-initiated process death
 * while the app is backgrounded. It does not survive:
 *   - swiping the app away from Recents
 *   - rebooting the phone
 *   - force-stop from Settings
 *
 * Those are by far the common ways users close a browser. This class
 * persists the tab list and active index to SharedPreferences so the
 * session comes back in all of the above cases.
 *
 * Serialization: one tab per line, "url TAB title". URLs never contain
 * tab characters (they are percent-encoded by loadUrl), so the tab is a
 * safe separator. Titles are sanitized to strip tabs and newlines.
 *
 * Writes happen on onStop -- the last reliable lifecycle hook before
 * the OS may reclaim the process. Reads happen on onCreate when no
 * Bundle is available.
 *
 * Cleared explicitly when the user taps Exit, so the next launch is a
 * fresh home tab.
 */
object SessionStore {

    private const val TAG = "SessionStore"
    private const val PREFS = "mb_session"
    private const val KEY_TABS = "tabs"
    private const val KEY_ACTIVE = "active"
    private const val SEP = "\t"
    private const val MAX_TABS = 6

    data class Snapshot(
        val urls: List<String>,
        val titles: List<String>,
        val activeIndex: Int
    )

    fun save(
        context: Context,
        urls: List<String>,
        titles: List<String>,
        activeIndex: Int
    ) {
        if (urls.isEmpty()) {
            clear(context)
            return
        }
        try {
            val n = minOf(urls.size, titles.size, MAX_TABS)
            if (n <= 0) {
                clear(context)
                return
            }
            val sb = StringBuilder(n * 96)
            for (i in 0 until n) {
                sb.append(urls[i])
                sb.append(SEP)
                sb.append(titles[i].replace('\n', ' ').replace('\t', ' '))
                if (i < n - 1) sb.append('\n')
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TABS, sb.toString())
                .putInt(KEY_ACTIVE, activeIndex.coerceIn(0, n - 1))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: " + t.message)
        }
    }

    fun load(context: Context): Snapshot? {
        return try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val blob = sp.getString(KEY_TABS, "") ?: return null
            if (blob.isEmpty()) return null
            val active = sp.getInt(KEY_ACTIVE, 0)
            val urls = ArrayList<String>(MAX_TABS)
            val titles = ArrayList<String>(MAX_TABS)
            for (line in blob.lineSequence()) {
                if (line.isEmpty()) continue
                val idx = line.indexOf(SEP)
                if (idx < 0) {
                    urls.add(line)
                    titles.add("")
                } else {
                    urls.add(line.substring(0, idx))
                    titles.add(line.substring(idx + 1))
                }
                if (urls.size >= MAX_TABS) break
            }
            if (urls.isEmpty()) null
            else Snapshot(urls, titles, active.coerceIn(0, urls.size - 1))
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: " + t.message)
            null
        }
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clear failed: " + t.message)
        }
    }
}
