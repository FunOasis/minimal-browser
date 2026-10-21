package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * Bounded, deduplicated visit history.
 *
 * Serialised as newline-separated records. Current format:
 *
 *     url|title|lastVisitedMs|visitCount
 *
 * Legacy entries written by previous builds as "url|title" still parse
 * correctly: they load with lastVisited=0 and visitCount=1 and are
 * rewritten in the new format the next time the same URL is recorded.
 *
 * The address-bar suggestion code in MainActivity only reads url and
 * title, so bumping the record format does not affect it.
 */
class HistoryStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_history", Context.MODE_PRIVATE)

    data class Entry(
        val url: String,
        val title: String,
        val lastVisited: Long = 0L,
        val visitCount: Int = 1
    )

    @Synchronized
    fun record(url: String, title: String) {
        if (url.isBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val now = System.currentTimeMillis()
        val current = loadAll().toMutableList()
        val existing = current.firstOrNull { it.url == url }
        current.removeAll { it.url == url }

        val newTitle = title.ifBlank {
            existing?.title?.takeIf { it.isNotBlank() } ?: prettyHost(url)
        }
        val newCount = (existing?.visitCount ?: 0) + 1
        current.add(0, Entry(url, newTitle, now, newCount))

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
                parseLine(line)
            }
            .toList()
    }

    @Synchronized
    fun remove(url: String) {
        val current = loadAll().toMutableList()
        if (current.removeAll { it.url == url }) saveAll(current)
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * Parses one line. Handles both the legacy 2-field format and the
     * current 4-field format. Titles may contain pipes (rare but legal
     * in HTML titles); we therefore treat only the LAST two fields as
     * metadata in the new format.
     */
    private fun parseLine(line: String): Entry? {
        val parts = line.split('|')
        return when {
            parts.size >= 4 -> {
                val url = parts[0]
                val last = parts[parts.size - 2].toLongOrNull() ?: 0L
                val count = parts[parts.size - 1].toIntOrNull() ?: 1
                val title = parts.subList(1, parts.size - 2).joinToString("|")
                if (url.isEmpty()) null else Entry(url, title, last, count)
            }
            parts.size >= 2 -> {
                val url = parts[0]
                val title = parts.subList(1, parts.size).joinToString("|")
                if (url.isEmpty()) null else Entry(url, title, 0L, 1)
            }
            else -> null
        }
    }

    private fun saveAll(entries: List<Entry>) {
        val blob = entries.joinToString("\n") { e ->
            e.url.replace('\n', ' ') + "|" +
                e.title.replace('\n', ' ').replace('|', ' ') + "|" +
                e.lastVisited + "|" +
                e.visitCount
        }
        sp.edit().putString(KEY_HISTORY, blob).apply()
    }

    private fun prettyHost(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    companion object {
        private const val KEY_HISTORY = "entries"
        private const val MAX_ENTRIES = 1000

        @Volatile private var inst: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            inst ?: synchronized(this) {
                inst ?: HistoryStore(context.applicationContext).also { inst = it }
            }
    }
}
