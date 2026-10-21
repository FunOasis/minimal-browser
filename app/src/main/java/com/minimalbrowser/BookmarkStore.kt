package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * Bookmark store.
 *
 * Serialised as newline-separated records in a dedicated
 * SharedPreferences file:
 *
 *     url|title|createdAtMs
 *
 * Titles may legally contain pipes; parseLine() treats only the LAST
 * field as metadata, so a title with an embedded pipe survives a
 * round-trip. Capped at MAX_ENTRIES; oldest entries are dropped on
 * overflow.
 *
 * No favicons are stored here. The BookmarksActivity row adapter asks
 * HistoryStore.loadFavicon(url) for each row -- if the user has ever
 * visited the bookmarked page, the icon is already on disk under
 * HistoryStore's cache; if not, the generic bookmark glyph shows.
 * That avoids duplicating the icon disk cache and its LruCache.
 */
class BookmarkStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_bookmarks", Context.MODE_PRIVATE)

    data class Entry(
        val url: String,
        val title: String,
        val createdAt: Long = 0L
    )

    @Synchronized
    fun add(url: String, title: String): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        val current = loadAll().toMutableList()
        if (current.any { it.url == url }) return false

        val now = System.currentTimeMillis()
        val t = title.ifBlank { prettyHost(url) }
        current.add(0, Entry(url, t, now))

        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }

        saveAll(current)
        return true
    }

    @Synchronized
    fun remove(url: String): Boolean {
        val current = loadAll().toMutableList()
        if (current.removeAll { it.url == url }) {
            saveAll(current)
            return true
        }
        return false
    }

    /**
     * Adds if absent, removes if present. Returns true when the URL is
     * bookmarked after the call, false when it is not.
     */
    @Synchronized
    fun toggle(url: String, title: String): Boolean {
        if (contains(url)) {
            remove(url)
            return false
        }
        add(url, title)
        return true
    }

    @Synchronized
    fun contains(url: String): Boolean = loadAll().any { it.url == url }

    @Synchronized
    fun loadAll(): List<Entry> {
        val blob = sp.getString(KEY_ITEMS, "") ?: return emptyList()
        if (blob.isEmpty()) return emptyList()
        return blob.lineSequence()
            .mapNotNull { line ->
                if (line.isEmpty()) return@mapNotNull null
                parseLine(line)
            }
            .toList()
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_ITEMS).apply()
    }

    private fun parseLine(line: String): Entry? {
        val parts = line.split('|')
        if (parts.size < 3) return null
        val url = parts[0]
        val created = parts[parts.size - 1].toLongOrNull() ?: 0L
        val title = parts.subList(1, parts.size - 1).joinToString("|")
        if (url.isEmpty()) return null
        return Entry(url, title, created)
    }

    private fun saveAll(entries: List<Entry>) {
        val blob = entries.joinToString("\n") { e ->
            e.url.replace('\n', ' ') + "|" +
                e.title.replace('\n', ' ').replace('|', ' ') + "|" +
                e.createdAt
        }
        sp.edit().putString(KEY_ITEMS, blob).apply()
    }

    private fun prettyHost(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_ENTRIES = 500

        @Volatile private var inst: BookmarkStore? = null

        fun get(context: Context): BookmarkStore =
            inst ?: synchronized(this) {
                inst ?: BookmarkStore(context.applicationContext).also { inst = it }
            }
    }
}
