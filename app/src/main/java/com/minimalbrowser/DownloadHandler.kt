package com.minimalbrowser

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Chrome-parity download handler for http(s) URLs.
 *
 * Chrome does not use Android's DownloadManager. It handles downloads
 * in-process, with the same headers, cookies, and TLS session as the
 * page. That is what this class does.
 *
 * Two entry paths:
 *   - BlobDownloadHelper  -> blob:/data: URLs (JS-mediated byte read)
 *   - DownloadHandler     -> http(s) URLs (direct HTTP in-process)
 *
 * This file now matches BlobDownloadHelper feature-for-feature:
 *   - Status-bar notification with determinate progress when the
 *     server sends Content-Length.
 *   - Completion notification on success or failure.
 *   - LocalDownloadsStore.record() on success so the file also appears
 *     in the app's Downloads screen.
 *
 * Storage strategy:
 *   API 29+: MediaStore.Downloads first, fall back to app-specific
 *   external Downloads.
 *   API 26-28: app-specific external Downloads. No runtime permission.
 *
 * Diagnostic toasts carry a suffix identifying the failing stage.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 10
    private const val CHUNK_SIZE = 64 * 1024

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val CD_FILENAME_RE =
        Regex("filename\\*?=(?:UTF-8'')?[\"']?([^\"';\\r\\n]+)", RegexOption.IGNORE_CASE)

    fun handle(
        activity: Activity,
        webView: WebView?,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        if (url.isBlank()) return

        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        Log.i(TAG, "handle scheme=" + scheme + " len=" + contentLength + " url=" + url)

        when (scheme) {
            "blob" -> {
                if (webView == null) {
                    toast(activity, activity.getString(R.string.download_failed))
                    return
                }
                val name = blobName(mimeType)
                BlobDownloadHelper.start(
                    activity = activity,
                    webView = webView,
                    blobUrl = url,
                    fileName = name,
                    mimeType = mimeType
                )
            }
            "data" -> {
                val name = blobName(mimeType)
                BlobDownloadHelper.startDataUrl(
                    activity = activity,
                    dataUrl = url,
                    fileName = name,
                    mimeType = mimeType
                )
            }
            "http", "https" -> {
                val referer = webView?.url
                val cookie = CookieManager.getInstance().getCookie(url)
                val filename = guessFilename(url, contentDisposition, mimeType)
                val ua = userAgent?.takeIf { it.isNotBlank() } ?: FALLBACK_UA

                toast(activity, activity.getString(R.string.download_started, filename))

                CoroutineScope(Dispatchers.IO).launch {
                    runDownload(
                        activity, url, filename, ua, referer, cookie,
                        mimeType, contentLength
                    )
                }
            }
            else -> {
                Log.w(TAG, "unsupported scheme: " + scheme)
                toast(activity, activity.getString(R.string.download_unsupported))
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        // Kept for API compatibility. The in-process downloader does
        // not require WRITE_EXTERNAL_STORAGE.
    }

    // -------------------------------------------------------------------------
    // Download loop
    // -------------------------------------------------------------------------

    private fun runDownload(
        activity: Activity,
        startUrl: String,
        filename: String,
        userAgent: String,
        referer: String?,
        cookie: String?,
        mimeType: String?,
        declaredLength: Long
    ) {
        var currentUrl = startUrl
        var hops = 0

        try {
            while (hops <= MAX_REDIRECTS) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }

                try {
                    val code = conn.responseCode
                    Log.i(TAG, "response " + code + " for " + currentUrl)

                    when {
                        code in 200..299 -> {
                            streamToDisk(activity, conn, filename, mimeType, declaredLength)
                            return
                        }
                        code in 300..399 -> {
                            val loc = conn.getHeaderField("Location")
                            if (loc.isNullOrBlank()) throw Exception("redirect no Location")
                            currentUrl = URL(URL(currentUrl), loc).toString()
                            hops++
                            Log.i(TAG, "redirect -> " + currentUrl)
                        }
                        else -> throw Exception("HTTP " + code)
                    }
                } finally {
                    conn.disconnect()
                }
            }
            throw Exception("too many redirects")
        } catch (t: Throwable) {
            Log.e(TAG, "download failed url=" + startUrl, t)
            DownloadNotifications.complete(activity, filename, success = false)
            toastOnUi(activity, activity.getString(R.string.download_failed) +
                ": " + t.javaClass.simpleName)
        }
    }

    private fun streamToDisk(
        activity: Activity,
        conn: HttpURLConnection,
        filename: String,
        mimeType: String?,
        declaredLength: Long
    ) {
        // Prefer the server's Content-Length for progress display;
        // fall back to the length the WebView declared.
        val serverLen = conn.contentLengthLong
        val totalBytes = if (serverLen > 0L) serverLen
                         else if (declaredLength > 0L) declaredLength
                         else -1L

        DownloadNotifications.start(activity, filename, totalBytes)

        val input = conn.inputStream
        var output: OutputStream? = null
        var mediaUri: Uri? = null
        var fileTarget: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore insert null")
                mediaUri = uri
                output = resolver.openOutputStream(uri)
                    ?: throw Exception("openOutputStream null")
            } else {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw Exception("no external files dir")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, filename)
                fileTarget = f
                output = FileOutputStream(f)
            }

            val buffer = ByteArray(CHUNK_SIZE)
            var bytesWritten = 0L
            var lastPct = -1

            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                bytesWritten += n

                if (totalBytes > 0L) {
                    val pct = ((bytesWritten * 100L) / totalBytes).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        DownloadNotifications.progress(
                            activity, filename, bytesWritten, totalBytes
                        )
                    }
                }
            }
            output.flush()
            Log.i(TAG, "wrote " + bytesWritten + " bytes to " + filename)

            if (mediaUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                activity.contentResolver.update(mediaUri, values, null, null)
                LocalDownloadsStore.get(activity).record(
                    filename,
                    mediaUri.toString(),
                    mimeType ?: "application/octet-stream",
                    bytesWritten
                )
            } else if (fileTarget != null) {
                LocalDownloadsStore.get(activity).record(
                    filename,
                    Uri.fromFile(fileTarget).toString(),
                    mimeType ?: "application/octet-stream",
                    bytesWritten
                )
            }

            DownloadNotifications.complete(activity, filename, success = true)
            toastOnUi(activity, activity.getString(R.string.download_saved, filename))
        } catch (t: Throwable) {
            Log.e(TAG, "stream failed", t)
            if (mediaUri != null) {
                try { activity.contentResolver.delete(mediaUri, null, null) }
                catch (_: Throwable) {}
            }
            fileTarget?.let { try { it.delete() } catch (_: Throwable) {} }
            DownloadNotifications.complete(activity, filename, success = false)
            toastOnUi(activity, activity.getString(R.string.download_failed) +
                ": " + t.javaClass.simpleName)
        } finally {
            try { output?.close() } catch (_: Throwable) {}
            try { input.close() } catch (_: Throwable) {}
        }
    }

    // -------------------------------------------------------------------------
    // Filename
    // -------------------------------------------------------------------------

    private fun blobName(mimeType: String?): String {
        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
    }

    private fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val urlName = runCatching { Uri.parse(url).lastPathSegment }
            .getOrNull()
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf { it.contains('.') && it != "/" }
        if (urlName != null) return sanitize(urlName)

        if (!contentDisposition.isNullOrBlank()) {
            val m = CD_FILENAME_RE.find(contentDisposition)?.groupValues?.getOrNull(1)
            if (!m.isNullOrBlank()) {
                val decoded = runCatching { URLDecoder.decode(m, "UTF-8") }.getOrDefault(m)
                return sanitize(decoded)
            }
        }

        return blobName(mimeType)
    }

    private fun sanitize(name: String): String {
        var s = name.replace('/', '_').replace('\\', '_').trim().trimEnd('.')
        if (s.isBlank() || s == "." || s == "..") s = "download"
        if (s.length > 180) {
            val ext = s.substringAfterLast('.', "")
            val stem = s.substringBeforeLast('.', s)
            val keep = (180 - (ext.length + 1)).coerceAtLeast(1)
            s = stem.take(keep) + if (ext.isBlank()) "" else "." + ext
        }
        return s
    }

    // -------------------------------------------------------------------------
    // Toast helpers
    // -------------------------------------------------------------------------

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toastOnUi(activity: Activity, msg: String) {
        try {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }
}
