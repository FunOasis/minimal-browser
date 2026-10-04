package com.minimalbrowser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast

/**
 * Bridges WebView's DownloadListener to Android's system DownloadManager.
 *
 * Why DownloadManager instead of writing the bytes ourselves?
 *   - The system handles the HTTP GET, resume-after-interruption, and
 *     network-type gating (Wi-Fi vs. metered) for free.
 *   - It writes to the correct scoped-storage location on every API
 *     level from 26 through 34 — including the MediaStore.Downloads
 *     path on API 29+ — without us touching SAF.
 *   - Progress and completion surface as a system notification with
 *     zero extra code, and the file appears in the user's Files app.
 *
 * The one wrinkle: on API 26-28, writing to the *public* Downloads
 * folder via DownloadManager still requires WRITE_EXTERNAL_STORAGE,
 * so we lazily request it on the first download and stash the pending
 * request until the user answers. On API 29+ no permission is needed.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val PERMISSION_REQUEST_CODE = 4101
    private const val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** The download that was waiting on a runtime permission grant. */
    @Volatile
    private var pending: PendingDownload? = null

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long
    )

    /**
     * Entry point from MainActivity's WebView.setDownloadListener.
     *
     * Returns immediately. If a storage permission is required and not
     * yet granted, the request is fired and this returns; the actual
     * enqueue happens in [onPermissionResult] once the user responds.
     */
    fun handle(
        activity: Activity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        if (url.isBlank()) return

        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme == "blob" || scheme == "data") {
            // These require JavaScript-side extraction (blob → base64 →
            // bridge call). Out of scope for a minimal browser; be honest
            // with the user rather than failing silently.
            toast(activity, activity.getString(R.string.download_unsupported))
            return
        }

        if (needsStoragePermission(activity)) {
            pending = PendingDownload(url, userAgent, contentDisposition, mimeType, contentLength)
            activity.requestPermissions(
                arrayOf(STORAGE_PERMISSION),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        enqueue(activity, url, userAgent, contentDisposition, mimeType)
    }

    /**
     * Called from MainActivity.onRequestPermissionsResult. If the code
     * matches our pending request, either enqueue the deferred download
     * or tell the user why it can't proceed.
     */
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val p = pending ?: return
        pending = null

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (granted) {
            enqueue(activity, p.url, p.userAgent, p.contentDisposition, p.mimeType)
        } else {
            toast(activity, activity.getString(R.string.download_permission_denied))
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun needsStoragePermission(context: Context): Boolean {
        // API 29+ writes via scoped storage — no permission needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return context.checkSelfPermission(STORAGE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val filename = guessFilename(url, contentDisposition, mimeType)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription(url)

                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)

                // Show the download progress notification, and keep a
                // "complete" entry afterwards so the user can tap it.
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )

                // Public Downloads folder → visible in Files, Downloads app,
                // and every other browser's download list.
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    filename
                )

                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            // Many file downloads are gated by session cookies or a
            // specific User-Agent. Forward both so logins "just work".
            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)
            }
            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            toast(context, context.getString(R.string.download_started, filename))
        } catch (t: Throwable) {
            Log.w(TAG, "enqueue failed: ${t.message}")
            toast(context, context.getString(R.string.download_failed))
        }
    }

    /**
     * Filename resolution priority:
     *   1. Content-Disposition header (attachment; filename="foo.pdf")
     *   2. Last path segment of the URL
     *   3. Timestamped fallback with extension derived from MIME type
     */
    private fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        // URLUtil handles the common Content-Disposition forms and returns
        // "downloadfile" when it can't produce anything useful.
        val fromHeaders = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull()

        if (!fromHeaders.isNullOrBlank() &&
            fromHeaders != "downloadfile" &&
            fromHeaders != "unknown"
        ) {
            return sanitize(fromHeaders)
        }

        val last = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        if (!last.isNullOrBlank()) {
            val cleaned = last.substringBefore('?').substringBefore('#')
            if (cleaned.isNotBlank()) return sanitize(cleaned)
        }

        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val suffix = if (ext.isNullOrBlank()) "" else ".$ext"
        return "download_${System.currentTimeMillis()}$suffix"
    }

    /**
     * Strip path separators and trim whitespace. DownloadManager rejects
     * filenames that contain '/' or '\' or that are entirely whitespace.
     */
    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim().ifBlank { "download" }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
