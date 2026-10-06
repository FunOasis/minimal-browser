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
 * Read/write facade over Android's system DownloadManager.
 *
 * The DownloadManager is the engine: it owns the transfer, survives
 * Doze, resumes after reboot, and posts the completion notification.
 * This class only reads its rows into a plain Kotlin data class and
 * forwards pause/resume/cancel/retry requests back to it.
 *
 * Why not a custom downloader? Because DownloadManager already gives
 * us everything a browser needs -- HTTP stack, notifications, retry,
 * pause/resume, scoped-storage-compatible destination -- without an
 * in-process foreground service to babysit. Chrome on Android uses it
 * too.
 *
 * Pause/Resume via reflection:
 *   DownloadManager.pauseDownload(long...) and resumeDownload(long...)
 *   are annotated @SystemApi -- they are present on every device at
 *   runtime but are stripped from the public compile SDK, so a direct
 *   call does not compile. We resolve them once via reflection and
 *   cache the Method handles. If either is missing at runtime (a
 *   hypothetical OEM build that removed them), supportsPauseResume
 *   returns false and the UI hides those actions.
 *
 * Query cost: listAll() is a single cursor walk over the app's own
 * download rows. At tens of rows this is sub-millisecond on the
 * platform side, cheap enough to poll once per second while the
 * Downloads screen is visible.
 */
class DownloadRepository(private val appContext: Context) {

    companion object {
        private const val TAG = "DownloadRepository"

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
        val lastModified: Long
    ) {
        val isRunning: Boolean
            get() = status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PENDING

        val isPaused: Boolean
            get() = status == DownloadManager.STATUS_PAUSED

        val isSuccess: Boolean
            get() = status == DownloadManager.STATUS_SUCCESSFUL

        val isFailed: Boolean
            get() = status == DownloadManager.STATUS_FAILED

        val isDone: Boolean
            get() = isSuccess || isFailed

        /** 0..100, or -1 if the total size is not yet known. */
        val progressPercent: Int
            get() {
                if (totalBytes <= 0L) return -1
                val p = (bytesDownloaded * 100L) / totalBytes
                return p.toInt().coerceIn(0, 100)
            }
    }

    init {
        resolveReflection()
    }

    private fun dm(): DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * True if this device's DownloadManager exposes pause/resume to
     * reflection. False on hypothetical OEM builds that stripped them.
     * The UI uses this to decide whether to offer those actions.
     */
    val supportsPauseResume: Boolean
        get() {
            resolveReflection()
            return pauseMethod != null && resumeMethod != null
        }

    // ----------------------------------------------------------------
    // Read
    // ----------------------------------------------------------------

    fun listAll(): List<Item> {
        // The public SDK does not expose a sort hint on
        // DownloadManager.Query, so we fetch all rows and sort them in
        // Kotlin below. The list is tiny (tens of rows at most), so the
        // cost is nil.
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
                try {
                    out.add(readRow(c))
                } catch (t: Throwable) {
                    Log.w(TAG, "row skipped: " + t.message)
                }
            }
        }
        // Newest first.
        out.sortByDescending { it.lastModified }
        return out
    }

    private fun readRow(c: Cursor): Item {
        val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        val url = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) ?: ""
        val title = c.getString(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
        ).orEmpty()
        val mime = c.getString(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
        ) ?: ""
        val status = c.getInt(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
        )
        val reason = c.getInt(
            c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
        )
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
        val name = title.ifBlank { deriveNameFromUrl(url) }
        return Item(id, url, name, mime, status, reason, bytes, total, localUri, modified)
    }

    private fun deriveNameFromUrl(url: String): String =
        Uri.parse(url).lastPathSegment ?: "download"

    /**
     * Content URI that other apps can safely open or receive. Returns
     * null if the download has not completed. Prefer this over
     * COLUMN_LOCAL_URI for Open/Share -- on API 24+ a file:// URI
     * passed across app boundaries throws FileUriExposedException.
     */
    fun fileUriFor(id: Long): Uri? = try {
        dm().getUriForDownloadedFile(id)
    } catch (t: Throwable) {
        Log.w(TAG, "fileUriFor failed: " + t.message)
        null
    }

    // ----------------------------------------------------------------
    // Control
    // ----------------------------------------------------------------

    fun pause(id: Long): Boolean {
        resolveReflection()
        val m = pauseMethod ?: return false
        return try {
            val ids = longArrayOf(id)
            m.invoke(dm(), ids)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "pause failed: " + t.message)
            false
        }
    }

    fun resume(id: Long): Boolean {
        resolveReflection()
        val m = resumeMethod ?: return false
        return try {
            val ids = longArrayOf(id)
            m.invoke(dm(), ids)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resume failed: " + t.message)
            false
        }
    }

    /** Cancel a running or paused download. Removes the row from the DB. */
    fun cancel(id: Long): Boolean {
        return try {
            val ids = longArrayOf(id)
            dm().remove(*ids) > 0
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed: " + t.message)
            false
        }
    }

    /**
     * Delete the downloaded file and remove the DB row. Safe on any
     * status: an active download is cancelled first, a partial file is
     * deleted along with the row.
     */
    fun deleteFile(item: Item): Boolean {
        var removed = false

        // Try the content resolver first. On API 29+ MediaStore owns the
        // row, and deleting it also drops DownloadManager's record.
        val uri = item.localUri
        if (uri != null) {
            try {
                val n = appContext.contentResolver.delete(uri, null, null)
                if (n > 0) removed = true
            } catch (t: Throwable) {
                Log.w(TAG, "contentResolver.delete failed: " + t.message)
            }
        }

        // Fallback A: file:// URI on API 26-28 where MediaStore owns a
        // separate row that the resolver may refuse to touch.
        if (!removed && uri != null && "file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                try {
                    val f = File(path)
                    if (f.exists() && f.delete()) removed = true
                } catch (t: Throwable) {
                    Log.w(TAG, "File.delete(uri) failed: " + t.message)
                }
            }
        }

        // Fallback B: raw file under the public Downloads folder, using
        // the display name. Covers cases where the URI was empty or
        // pointed somewhere unexpected.
        if (!removed) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val f = File(dir, item.fileName)
                if (f.exists() && f.delete()) removed = true
            } catch (t: Throwable) {
                Log.w(TAG, "File.delete(name) failed: " + t.message)
            }
        }

        // Always scrub the DownloadManager row so the list actually
        // empties even if the file was already gone.
        try {
            val ids = longArrayOf(item.id)
            dm().remove(*ids)
        } catch (_: Throwable) {
            // ignore
        }

        return removed
    }

    /**
     * Remove the row from DownloadManager without touching the file.
     * Used to hide a completed download from our list while keeping
     * the actual file in the public Downloads folder.
     */
    fun removeFromList(id: Long): Boolean {
        return try {
            val ids = longArrayOf(id)
            dm().remove(*ids) > 0
        } catch (t: Throwable) {
            Log.w(TAG, "remove failed: " + t.message)
            false
        }
    }

    /**
     * Re-enqueue a failed download. Uses the original URL, filename,
     * and MIME type; the failed row is removed on success.
     * Returns the new download id, or null on failure.
     */
    fun retry(item: Item): Long? {
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
            try {
                val ids = longArrayOf(item.id)
                dm().remove(*ids)
            } catch (_: Throwable) {
                // ignore
            }
            newId
        } catch (t: Throwable) {
            Log.w(TAG, "retry failed: " + t.message)
            null
        }
    }

    /**
     * Remove every finished (successful or failed) row from the list.
     * Files on disk are left untouched.
     */
    fun clearCompleted(): Int {
        var count = 0
        for (item in listAll()) {
            if (item.isDone && removeFromList(item.id)) count++
        }
        return count
    }
}
