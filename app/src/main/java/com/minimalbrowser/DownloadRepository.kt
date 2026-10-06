package com.minimalbrowser

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.lang.reflect.Method

/**
 * Read/write facade over two sources of downloads:
 *
 *   1. The system DownloadManager (http/https downloads enqueued by
 *      the old code path and by any future path that wants the system
 *      transfer engine).
 *   2. LocalDownloadsStore (files written by our in-process downloader
 *      for blob: and data: URLs).
 *
 * listAll() merges both, newest first. Actions dispatch on Item.source:
 * pause/resume/cancel/retry are DownloadManager-only; open/share/delete
 * work on both.
 */
class DownloadRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "DownloadRepository"
        const val SOURCE_DM = 0
        const val SOURCE_LOCAL = 1

        @Volatile private var pauseMethod: Method? = null
        @Volatile private var resumeMethod: Method? = null
        @Volatile private var reflectionResolved = false

        @Synchronized
        private fun resolveReflection() {
            if (reflectionResolved) return
            reflectionResolved = true
            try {
                pauseMethod = DownloadManager::class.java
                    .getMethod("pauseDownload", LongArray::class.java)
            } catch (t: Throwable) {
                Log.i(TAG, "pauseDownload not reachable: " + t.message)
                pauseMethod = null
            }
            try {
                resumeMethod = DownloadManager::class.java
                    .getMethod("resumeDownload", LongArray::class.java)
            } catch (t: Throwable) {
                Log.i(TAG, "resumeDownload not reachable: " + t.message)
                resumeMethod = null
            }
        }
    }

    data class Item(
        val id: Long,
        val url: String,
        val fileName: String,
        val mimeType: String,
        val status: Int,
        val reason: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val localUri: Uri?,
        val lastModified: Long,
        val source: Int
    ) {
        val isLocal: Boolean get() = source == SOURCE_LOCAL
        val isRunning: Boolean
            get() = !isLocal && (status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PENDING)
        val isPaused: Boolean
            get() = !isLocal && status == DownloadManager.STATUS_PAUSED
        val isSuccess: Boolean
            get() = isLocal || status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed: Boolean
            get() = !isLocal && status == DownloadManager.STATUS_FAILED
        val isDone: Boolean get() = isSuccess || isFailed
        val progressPercent: Int
            get() {
                if (isLocal) return 100
                if (totalBytes <= 0L) return -1
                val p = (bytesDownloaded * 100L) / totalBytes
                return p.toInt().coerceIn(0, 100)
            }
    }

    init { resolveReflection() }

    private fun dm(): DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val supportsPauseResume: Boolean
        get() {
            resolveReflection()
            return pauseMethod != null && resumeMethod != null
        }

    // ----------------------------------------------------------------
    // Read (merged)
    // ----------------------------------------------------------------

    fun listAll(): List<Item> {
        val dmItems = readDownloadManager()
        val localItems = readLocal()
        return (dmItems + localItems).sortedByDescending { it.lastModified }
    }

    private fun readDownloadManager(): List<Item> {
        val query = DownloadManager.Query()
        val cursor: Cursor? = try {
            dm().query(query)
        } catch (t: Throwable) {
            Log.w(TAG, "query failed: " + t.message)
            return emptyList()
        }
        if (cursor == null) return emptyList()
        val out = ArrayList<Item>(cursor.count)
        cursor.use { c ->
            while (c.moveToNext()) {
                try { out.add(readRow(c)) }
                catch (t: Throwable) { Log.w(TAG, "row skipped: " + t.message) }
            }
        }
        return out
    }

    private fun readLocal(): List<Item> {
        return try {
            LocalDownloadsStore.get(appContext).listAll().map { e ->
                Item(
                    id = e.id,
                    url = e.uri,
                    fileName = e.name,
                    mimeType = e.mime,
                    status = DownloadManager.STATUS_SUCCESSFUL,
                    reason = 0,
                    bytesDownloaded = e.size,
                    totalBytes = e.size,
                    localUri = Uri.parse(e.uri),
                    lastModified = e.timestamp,
                    source = SOURCE_LOCAL
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readLocal failed: " + t.message)
            emptyList()
        }
    }

    private fun readRow(c: Cursor): Item {
        val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        val url = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) ?: ""
        val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
        val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)) ?: ""
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val bytes = c.getLong(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        )
        val total = c.getLong(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        )
        val localStr = c.getString(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
        )
        val localUri = localStr?.takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        val modified = c.getLong(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
        )
        val name = title.ifBlank { Uri.parse(url).lastPathSegment ?: "download" }
        return Item(
            id, url, name, mime, status, reason, bytes, total,
            localUri, modified, SOURCE_DM
        )
    }

    fun fileUriFor(item: Item): Uri? {
        if (item.isLocal) return item.localUri
        return try { dm().getUriForDownloadedFile(item.id) }
        catch (t: Throwable) {
            Log.w(TAG, "fileUriFor failed: " + t.message)
            null
        }
    }

    // ----------------------------------------------------------------
    // Control
    // ----------------------------------------------------------------

    fun pause(item: Item): Boolean {
        if (item.isLocal) return false
        resolveReflection()
        val m = pauseMethod ?: return false
        return try { m.invoke(dm(), longArrayOf(item.id)); true }
        catch (t: Throwable) { Log.w(TAG, "pause failed: " + t.message); false }
    }

    fun resume(item: Item): Boolean {
        if (item.isLocal) return false
        resolveReflection()
        val m = resumeMethod ?: return false
        return try { m.invoke(dm(), longArrayOf(item.id)); true }
        catch (t: Throwable) { Log.w(TAG, "resume failed: " + t.message); false }
    }

    fun cancel(item: Item): Boolean {
        if (item.isLocal) return false
        return try { dm().remove(item.id) > 0 }
        catch (t: Throwable) { Log.w(TAG, "cancel failed: " + t.message); false }
    }

    fun deleteFile(item: Item): Boolean {
        if (item.isLocal) {
            var ok = false
            item.localUri?.let { uri ->
                // If it's a MediaStore URI, deleting through the content
                // resolver removes both the row and the file. A file://
                // URI needs the File API instead.
                try {
                    val n = appContext.contentResolver.delete(uri, null, null)
                    if (n > 0) ok = true
                } catch (_: Throwable) {}
                if (!ok && "file".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.path
                    if (!path.isNullOrBlank()) {
                        try { if (File(path).delete()) ok = true } catch (_: Throwable) {}
                    }
                }
            }
            LocalDownloadsStore.get(appContext).remove(item.url)
            return ok || true // the entry is gone from our list either way
        }

        var removed = false
        item.localUri?.let { uri ->
            try {
                if (appContext.contentResolver.delete(uri, null, null) > 0) removed = true
            } catch (t: Throwable) { Log.w(TAG, "resolver delete: " + t.message) }
            if (!removed && "file".equals(uri.scheme, ignoreCase = true)) {
                uri.path?.let { p ->
                    try { if (File(p).delete()) removed = true } catch (_: Throwable) {}
                }
            }
        }
        if (!removed) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (File(dir, item.fileName).delete()) removed = true
            } catch (_: Throwable) {}
        }
        try { dm().remove(item.id) } catch (_: Throwable) {}
        return removed
    }

    fun removeFromList(item: Item): Boolean {
        if (item.isLocal) {
            LocalDownloadsStore.get(appContext).remove(item.url)
            return true
        }
        return try { dm().remove(item.id) > 0 }
        catch (t: Throwable) { Log.w(TAG, "remove failed: " + t.message); false }
    }

    fun retry(item: Item): Long? {
        if (item.isLocal) return null
        return try {
            val request = DownloadManager.Request(Uri.parse(item.url)).apply {
                setTitle(item.fileName)
                setDescription(item.url)
                if (item.mimeType.isNotBlank()) setMimeType(item.mimeType)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    item.fileName
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val newId = dm().enqueue(request)
            try { dm().remove(item.id) } catch (_: Throwable) {}
            newId
        } catch (t: Throwable) {
            Log.w(TAG, "retry failed: " + t.message)
            null
        }
    }

    fun clearCompleted(): Int {
        var count = 0
        for (item in listAll()) {
            if (item.isDone && removeFromList(item)) count++
        }
        return count
    }
}
