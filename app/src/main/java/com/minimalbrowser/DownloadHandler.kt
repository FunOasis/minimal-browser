package com.minimalbrowser

import android.app.Activity
import android.content.ContentValues
import android.content.Context
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Chrome-parity download handler.
 *
 * Chrome does NOT use Android's DownloadManager. Since M54 it handles
 * all downloads in-process through its own network stack[citation:7].
 * This matters because DownloadManager runs in a separate process with
 * no access to the WebView's cookies, session, or TLS state[citation:17].
 * That's why GitHub raw files fail: the system downloader lacks the
 * authenticated session context.
 *
 * This implementation mirrors Chrome: it opens an HttpURLConnection
 * with the page's own headers (User-Agent, Cookie, Referer, Accept),
 * follows redirects, and streams the response directly to disk.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 10
    private const val CHUNK_SIZE = 64 * 1024

    // Chrome's own UA string. GitHub's CDN (Fastly) expects a browser UA.
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        Log.i(TAG, "handle scheme=$scheme url=$url")

        // Blob and data URLs cannot be fetched externally.
        if (scheme == "blob" || scheme == "data") {
            Toast.makeText(activity, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        if (scheme != "http" && scheme != "https") {
            Toast.makeText(activity, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        // Get the page URL for the Referer header — this is what Chrome sends.
        val referer = webView?.url

        // Capture cookies from the WebView's session.
        val cookie = CookieManager.getInstance().getCookie(url)

        val filename = guessFilename(url, contentDisposition, mimeType)
        val effectiveUA = userAgent?.takeIf { it.isNotBlank() } ?: FALLBACK_UA

        CoroutineScope(Dispatchers.IO).launch {
            downloadToDisk(activity, url, filename, effectiveUA, referer, cookie, mimeType)
        }
    }

    private fun downloadToDisk(
        activity: Activity,
        url: String,
        filename: String,
        userAgent: String,
        referer: String?,
        cookie: String?,
        mimeType: String?
    ) {
        var connection: HttpURLConnection? = null
        var currentUrl = url
        var redirects = 0

        try {
            while (redirects <= MAX_REDIRECTS) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = "GET"

                    // Chrome sends these headers on every download request.
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    if (referer != null) setRequestProperty("Referer", referer)
                    if (cookie != null) setRequestProperty("Cookie", cookie)
                }

                val responseCode = connection.responseCode
                Log.i(TAG, "response code=$responseCode for $currentUrl")

                when {
                    responseCode in 200..299 -> {
                        // Success — stream to disk.
                        streamToFile(activity, connection, filename, mimeType)
                        return
                    }
                    responseCode in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            throw Exception("Redirect without Location")
                        }
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirects++
                        Log.i(TAG, "following redirect to $currentUrl")
                        connection.disconnect()
                    }
                    else -> {
                        throw Exception("HTTP $responseCode")
                    }
                }
            }
            throw Exception("Too many redirects")
        } catch (t: Throwable) {
            Log.e(TAG, "download failed url=$url", t)
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.download_failed) + ": " + t.javaClass.simpleName,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun streamToFile(
        activity: Activity,
        connection: HttpURLConnection,
        filename: String,
        mimeType: String?
    ) {
        val input = connection.inputStream

        // Create the output destination. On API 29+ use MediaStore.
        val output: java.io.OutputStream
        val finalName: String

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("MediaStore insert failed")
            output = resolver.openOutputStream(uri)
                ?: throw Exception("Cannot open output stream")
            finalName = filename

            // Stream the data.
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var totalBytes = 0L
            while (input.read(buffer).also { bytesRead = it } > 0) {
                output.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
            output.flush()
            output.close()
            input.close()

            // Mark the file as complete.
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            Log.i(TAG, "saved $finalName ($totalBytes bytes)")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            output = FileOutputStream(file)
            finalName = filename

            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var totalBytes = 0L
            while (input.read(buffer).also { bytesRead = it } > 0) {
                output.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
            output.flush()
            output.close()
            input.close()

            Log.i(TAG, "saved $finalName ($totalBytes bytes)")
        }

        activity.runOnUiThread {
            Toast.makeText(
                activity,
                activity.getString(R.string.download_saved, finalName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        // Try URL last segment first.
        val urlName = Uri.parse(url).lastPathSegment
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf { it.contains('.') }

        if (urlName != null) return sanitize(urlName)

        // Try Content-Disposition.
        contentDisposition?.let { cd ->
            val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';\n]+)""")
                .find(cd)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                val decoded = URLDecoder.decode(match, "UTF-8")
                return sanitize(decoded)
            }
        }

        // Fall back to MIME-based name.
        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
    }

    private fun sanitize(name: String): String {
        return name.replace('/', '_').replace('\\', '_').trim()
    }
}
