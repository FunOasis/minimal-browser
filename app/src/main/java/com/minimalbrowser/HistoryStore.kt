package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * Bounded, deduplicated visit history used to power address-bar
 * suggestions. Stored as a single newline-separated blob in
 * SharedPreferences, newest first. Not a full history database —
 * just enough for autocomplete.
 *
 * Lines are of the form:  url|title
 * The title may be empty, in which case the trailing pipe is still
 * present so the split is stable.
 */
class HistoryStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_history", Context.MODE_PRIVATE)

    data class Entry(val url: String, val title: String)

    @Synchronized
    fun record(url: String, title: String) {
        if (url.isBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val current = loadAll().toMutableList()
        current.removeAll { it.url == url }
        current.add(0, Entry(url, title.ifBlank { prettyHost(url) }))

        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }

        saveAll(current)
    }

    @Synchronized
    fun loadAll(): List<Entry> {
        val blob = sp.getString(KEY_HISTORY, "") ?: return emptyList()
        if (blob.isEmpty()) return emptyList()
        return blob.lineSequence()
            .mapNotNull { line ->
                if (line.isEmpty()) return@mapNotNull null
                val idx = line.indexOf('|')
                if (idx < 0) return@mapNotNull null
                val u = line.substring(0, idx)
                val t = line.substring(idx + 1)
                if (u.isEmpty()) null else Entry(u, t)
            }
            .toList()
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveAll(entries: List<Entry>) {
        val blob = entries.joinToString("\n") { e ->
            e.url + "|" + e.title.replace('\n', ' ')
        }
        sp.edit().putString(KEY_HISTORY, blob).apply()
    }

    private fun prettyHost(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    companion object {
        private const val KEY_HISTORY = "entries"
        private const val MAX_ENTRIES = 300

        @Volatile private var inst: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            inst ?: synchronized(this) {
                inst ?: HistoryStore(context.applicationContext).also { inst = it }
            }
    }
}
