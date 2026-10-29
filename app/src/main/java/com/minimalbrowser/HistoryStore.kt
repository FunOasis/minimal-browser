package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bounded, deduplicated visit history.
 *
 * Serialised as newline-separated records:
 *
 *     url|title|lastVisitedMs|visitCount
 *
 * Legacy entries written by previous builds as "url|title" still parse
 * correctly: they load with lastVisited=0 and visitCount=1 and are
 * rewritten in the new format the next time the same URL is recorded.
 *
 * Favicons live in filesDir/history_icons/ as PNG files keyed by the
 * SHA-1 of the URL. Deleted whenever the corresponding entry is
 * removed, cleared, or aged out by clearOlderThan(). An in-memory
 * LruCache cuts the disk hit on repeated list scrolls to near zero.
 *
 * In-memory entry cache
 * ---------------------
 * loadAll() is on the address-bar suggestion hot path: it runs on
 * every keystroke. Parsing the newline blob each time scaled with
 * history size and produced 7 full parses for a 7-character query.
 * The parsed entry list is now cached, invalidated only by the four
 * mutating methods below (record / remove / clear / clearOlderThan).
 * All four hold the instance monitor, so the cache cannot be seen
 * half-updated.
 *
 * Ordering-safe record
 * --------------------
 * record() now takes an explicit timestamp. MainActivity captures
 * the timestamp on the UI thread and passes it into a background
 * coroutine, so two racing records preserve visit order even if the
 * coroutines complete out of order.
 */
class HistoryStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("minimal_browser_history", Context.MODE_PRIVATE)

    private val iconsDir: File = File(context.filesDir, "history_icons").apply {
        if (!exists()) mkdirs()
    }

    /** Small in-memory bitmap cache so list scrolling does not re-read disk. */
    private val iconCache = object : LruCache<String, Bitmap>(100) {}

    /** Parsed entry cache. Null = not yet loaded. Never written outside this class. */
    @Volatile private var _cache: List<Entry>? = null

    data class Entry(
        val url: String,
        val title: String,
        val lastVisited: Long = 0L,
        val visitCount: Int = 1
    )

    @Synchronized
    fun record(
        url: String,
        title: String,
        favicon: Bitmap? = null,
        now: Long = System.currentTimeMillis()
    ) {
        if (url.isBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val current = loadAll().toMutableList()
        val existing = current.firstOrNull { it.url == url }
        current.removeAll { it.url == url }

        // Persist the favicon if we have a fresh one. Never overwrite a
        // previously stored icon with nothing — a transient null favicon
        // (slow icon load, redirect chain) would otherwise destroy it.
        if (favicon != null) saveFavicon(url, favicon)

        val newTitle = title.ifBlank {
            existing?.title?.takeIf { it.isNotBlank() } ?: prettyHost(url)
        }
        val newCount = (existing?.visitCount ?: 0) + 1
        current.add(0, Entry(url, newTitle, now, newCount))

        if (current.size > MAX_ENTRIES) {
            val removed = current.subList(MAX_ENTRIES, current.size).toList()
            for (e in removed) {
                deleteFavicon(e.url)
                iconCache.remove(e.url)
            }
            current.subList(MAX_ENTRIES, current.size).clear()
        }

        saveAll(current)
        _cache = current
    }

    @Synchronized
    fun loadAll(): List<Entry> {
        _cache?.let { return it }
        val blob = sp.getString(KEY_HISTORY, "") ?: return emptyList<Entry>().also {
            _cache = it
        }
        if (blob.isEmpty()) return emptyList<Entry>().also { _cache = it }
        val parsed = blob.lineSequence()
            .mapNotNull { line ->
                if (line.isEmpty()) null else parseLine(line)
            }
            .toList()
        _cache = parsed
        return parsed
    }

    @Synchronized
    fun remove(url: String) {
        val current = loadAll().toMutableList()
        if (current.removeAll { it.url == url }) {
            saveAll(current)
            _cache = current
            deleteFavicon(url)
            iconCache.remove(url)
        }
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_HISTORY).apply()
        try { iconsDir.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
        iconCache.evictAll()
        _cache = emptyList()
    }

    /**
     * Removes every entry whose lastVisited is older than `days` days.
     *
     * Entries with lastVisited == 0 (legacy records from before the
     * timestamp upgrade, or imported blobs) are preserved on purpose:
     * they represent history the user has never seen a "recent" timestamp
     * for, so a periodic-prune should not delete them silently. They can
     * still be removed by "Clear all".
     *
     * Returns the number of entries removed.
     */
    @Synchronized
    fun clearOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        val current = loadAll().toMutableList()
        val toRemove = current.filter { it.lastVisited > 0L && it.lastVisited < cutoff }
        if (toRemove.isEmpty()) return 0
        for (e in toRemove) {
            deleteFavicon(e.url)
            iconCache.remove(e.url)
        }
        current.removeAll(toRemove)
        saveAll(current)
        _cache = current
        return toRemove.size
    }

    /**
     * Returns the cached favicon for a URL, or null if none has been
     * stored. Safe to call from a background thread; the in-memory
     * cache is LruCache-synchronised internally, and this method
     * touches no shared history state.
     */
    fun loadFavicon(url: String): Bitmap? {
        iconCache.get(url)?.let { return it }
        return try {
            val f = iconFile(url)
            if (!f.exists()) return null
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            iconCache.put(url, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Favicon storage
    // ------------------------------------------------------------------

    private fun iconFile(url: String): File =
        File(iconsDir, sha1Hex(url) + ".png")

    private fun saveFavicon(url: String, src: Bitmap) {
        try {
            val scaled = resizeFavicon(src, 64)
            FileOutputStream(iconFile(url)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            iconCache.put(url, scaled)
        } catch (_: Throwable) {
            // Silent. A missing favicon is a cosmetic loss, not a
            // correctness issue, and an exception here would abort the
            // whole record() call.
        }
    }

    private fun deleteFavicon(url: String) {
        try { iconFile(url).delete() } catch (_: Throwable) {}
    }

    /**
     * Cap the stored icon at maxPx on the longest edge. WebView favicons
     * range from 16x16 to 256x256 depending on the site; storing them
     * uncapped would mean 50-100 KB per entry for a handful of sites
     * that serve large icons. Returns the original bitmap unchanged if
     * it is already within bounds — callers must NOT recycle the result.
     */
    private fun resizeFavicon(src: Bitmap, maxPx: Int): Bitmap {
        if (src.width <= maxPx && src.height <= maxPx) return src
        val scale = minOf(
            maxPx.toFloat() / src.width,
            maxPx.toFloat() / src.height
        )
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun sha1Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder(40)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v shr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

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
