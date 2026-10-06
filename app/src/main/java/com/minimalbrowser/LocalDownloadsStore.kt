package com.minimalbrowser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local record of files this app wrote itself.
 *
 * The in-process downloader (BlobDownloadHelper, DownloadHandler) does
 * not go through DownloadManager, so its files never appear in that
 * system table -- and therefore never appear in the app's Downloads
 * screen, which reads from DownloadManager. This store fills the gap.
 *
 * DownloadsActivity merges these entries with the DownloadManager rows.
 *
 * Capped at MAX_ENTRIES. Oldest entries are dropped on overflow. The
 * files themselves are NOT deleted; only the record is trimmed.
 */
class LocalDownloadsStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("mb_local_downloads", Context.MODE_PRIVATE)

    data class Entry(
        val id: Long,
        val name: String,
        val uri: String,
        val mime: String,
        val size: Long,
        val timestamp: Long
    )

    @Synchronized
    fun record(name: String, uri: String, mime: String, size: Long) {
        if (name.isBlank() || uri.isBlank()) return
        val list = load().toMutableList()
        // Replace any prior record with the same URI.
        list.removeAll { it.uri == uri }
        list.add(
            0,
            Entry(
                id = System.currentTimeMillis(),
                name = name,
                uri = uri,
                mime = mime.ifBlank { "application/octet-stream" },
                size = size,
                timestamp = System.currentTimeMillis()
            )
        )
        if (list.size > MAX_ENTRIES) {
            list.subList(MAX_ENTRIES, list.size).clear()
        }
        save(list)
    }

    @Synchronized
    fun listAll(): List<Entry> = load()

    @Synchronized
    fun remove(uri: String) {
        val list = load().toMutableList()
        list.removeAll { it.uri == uri }
        save(list)
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_ENTRIES).apply()
    }

    private fun load(): List<Entry> {
        val blob = sp.getString(KEY_ENTRIES, "") ?: return emptyList()
        if (blob.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(blob)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri", "")
                val name = o.optString("name", "")
                if (uri.isBlank() || name.isBlank()) continue
                out.add(
                    Entry(
                        id = o.optLong("id", 0L),
                        name = name,
                        uri = uri,
                        mime = o.optString("mime", "application/octet-stream"),
                        size = o.optLong("size", 0L),
                        timestamp = o.optLong("timestamp", 0L)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: " + t.message)
            emptyList()
        }
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("uri", e.uri)
                    put("mime", e.mime)
                    put("size", e.size)
                    put("timestamp", e.timestamp)
                }
            )
        }
        sp.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "LocalDownloadsStore"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 200

        @Volatile private var inst: LocalDownloadsStore? = null

        fun get(context: Context): LocalDownloadsStore =
            inst ?: synchronized(this) {
                inst ?: LocalDownloadsStore(context.applicationContext).also { inst = it }
            }
    }
}
