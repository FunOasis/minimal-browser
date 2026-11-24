package com.minimalbrowser

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

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
 * Threading / lifecycle:
 *   The download coroutine runs on Dispatchers.IO and can outlive the
 *   Activity that started it (user presses back/home mid-download).
 *   It therefore captures ONLY the application context -- never the
 *   Activity -- so a long transfer can never leak a destroyed
 *   Activity. Toasts are posted to the main thread with the
 *   application context.
 *
 * Diagnostic toasts carry a suffix identifying the failing stage.
 *
 * Cross-origin redirect hardening
 * --------------------------------
 * An earlier version of runDownload() captured the page's Cookie and
 * Referer ONCE, before the first request, and resent that exact same
 * pair of headers on every subsequent hop of a redirect chain -- even
 * when a 3xx response sent the chain to a completely different host.
 * That let any redirect (including one the original server itself did
 * not control, e.g. a compromised or malicious intermediate CDN) read
 * the session cookie and the referring page's URL.
 *
 * This version fixes that in two independent ways:
 *   - Cookie: re-queried from CookieManager for the EXACT url of every
 *     hop, rather than reused across hops. CookieManager already
 *     enforces correct domain/path scoping, so a host that should not
 *     see a cookie simply never gets one -- we just have to stop
 *     bypassing that by caching a stale value.
 *   - Referer: recomputed for every hop against the page that actually
 *     initiated the download, using a strict-origin-when-cross-origin
 *     policy (same policy Chrome defaults to): same-origin keeps the
 *     full referrer, cross-origin keeps the origin only, and any
 *     https -> http downgrade sends no referrer at all.
 *
 * All of the policy decisions above (classifyRedirect / refererForHop /
 * originOf / clampProgressPercent / filename parsing) are implemented
 * as small internal pure functions with no Android dependency, so they
 * can be unit tested directly -- see DownloadFilenameTest.kt and
 * RedirectPolicyTest.kt.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 10
    private const val CHUNK_SIZE = 64 * 1024

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // RFC 6266 extended notation: filename*=charset'lang'percent-encoded-value.
    // Checked FIRST and independently of the plain `filename=` parameter,
    // because when a server sends both (the common backward-compatible
    // pattern), the extended one is the one that correctly represents
    // non-ASCII names and must win.
    private val CD_FILENAME_STAR_RE =
        Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)

    // Plain RFC 6266 filename="...": a quoted-string (or bare token).
    // Deliberately NOT percent- or '+'-decoded: unlike filename*, this
    // parameter carries literal characters, so running it through
    // URLDecoder (as an earlier version did) silently mangled names
    // containing a literal '+' (e.g. "C++ notes.txt").
    private val CD_FILENAME_RE =
        Regex("""filename\s*=\s*"?([^";\r\n]*)"?""", RegexOption.IGNORE_CASE)

    private val mainHandler = Handler(Looper.getMainLooper())

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
                // Only the referring PAGE's url is captured up front. The
                // Cookie header is deliberately NOT prefetched here -- it
                // is looked up fresh, per hop, inside runDownload() so a
                // redirect can never be handed a stale cookie scoped to a
                // different host. See the class doc.
                val referrerPageUrl = webView?.url
                val filename = guessFilename(url, contentDisposition, mimeType)
                val ua = userAgent?.takeIf { it.isNotBlank() } ?: FALLBACK_UA

                toast(activity, activity.getString(R.string.download_started, filename))

                // Everything past this point may outlive the Activity.
                val app = activity.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    runDownload(
                        app, url, filename, ua, referrerPageUrl,
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
        app: Context,
        startUrl: String,
        filename: String,
        userAgent: String,
        referrerPageUrl: String?,
        mimeType: String?,
        declaredLength: Long
    ) {
        var currentUrl = startUrl
        var hops = 0

        try {
            while (hops <= MAX_REDIRECTS) {
                // Recomputed on EVERY hop -- never cached across
                // redirects. See the class doc for why.
                val referer = refererForHop(referrerPageUrl, currentUrl)
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(currentUrl)
                }.getOrNull()

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
                            streamToDisk(app, conn, filename, mimeType, declaredLength)
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
            DownloadNotifications.complete(app, filename, success = false)
            toastOnUi(app, app.getString(R.string.download_failed) +
                ": " + t.javaClass.simpleName)
        }
    }

    private fun streamToDisk(
        app: Context,
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

        DownloadNotifications.start(app, filename, totalBytes)

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
                val resolver = app.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore insert null")
                mediaUri = uri
                output = resolver.openOutputStream(uri)
                    ?: throw Exception("openOutputStream null")
            } else {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw Exception("no external files dir")
                if (!dir.exists()) dir.mkdirs()
                // Never silently truncate an existing file with the same
                // name: MediaStore auto-uniquifies on 29+, the File API
                // does not.
                val f = uniqueFile(dir, filename)
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

                val pct = clampProgressPercent(bytesWritten, totalBytes)
                if (pct >= 0 && pct != lastPct) {
                    lastPct = pct
                    DownloadNotifications.progress(
                        app, filename, bytesWritten, totalBytes
                    )
                }
            }
            output.flush()
            // A clean EOF before Content-Length means the connection was
            // cut mid-transfer. Without this check the truncated file is
            // reported as a successful download.
            if (totalBytes > 0L && bytesWritten != totalBytes) {
                throw java.io.IOException(
                    "truncated: got " + bytesWritten + " of " + totalBytes + " bytes"
                )
            }
            Log.i(TAG, "wrote " + bytesWritten + " bytes to " + filename)

            if (mediaUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                app.contentResolver.update(mediaUri, values, null, null)
                LocalDownloadsStore.get(app).record(
                    filename,
                    mediaUri.toString(),
                    mimeType ?: "application/octet-stream",
                    bytesWritten
                )
            } else if (fileTarget != null) {
                LocalDownloadsStore.get(app).record(
                    filename,
                    Uri.fromFile(fileTarget).toString(),
                    mimeType ?: "application/octet-stream",
                    bytesWritten
                )
            }

            DownloadNotifications.complete(app, filename, success = true)
            toastOnUi(app, app.getString(R.string.download_saved, filename))
        } catch (t: Throwable) {
            Log.e(TAG, "stream failed", t)
            if (mediaUri != null) {
                try { app.contentResolver.delete(mediaUri, null, null) }
                catch (_: Throwable) {}
            }
            fileTarget?.let { try { it.delete() } catch (_: Throwable) {} }
            DownloadNotifications.complete(app, filename, success = false)
            toastOnUi(app, app.getString(R.string.download_failed) +
                ": " + t.javaClass.simpleName)
        } finally {
            try { output?.close() } catch (_: Throwable) {}
            try { input.close() } catch (_: Throwable) {}
        }
    }

    // -------------------------------------------------------------------------
    // Filename (pure / unit-testable -- see DownloadFilenameTest.kt)
    // -------------------------------------------------------------------------

    private fun blobName(mimeType: String?): String {
        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
    }

    /**
     * Decide the save name for an http(s) download.
     *
     * Content-Disposition is the server's explicit, authoritative
     * statement of what the file should be called and now wins over a
     * name merely inferred from the URL path. An earlier version
     * preferred the URL-derived name unconditionally, which meant a
     * server's real filename (e.g. from a signed/opaque download
     * endpoint whose path is just an id) was silently ignored or
     * misrepresented.
     */
    internal fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val fromHeader = parseContentDispositionFilename(contentDisposition)
        if (!fromHeader.isNullOrBlank()) return sanitize(fromHeader)

        val fromUrl = urlFileNameCandidate(url)
        if (fromUrl != null) return sanitize(fromUrl)

        return blobName(mimeType)
    }

    /**
     * Pure, Android-independent replacement for the old
     * `Uri.parse(url).lastPathSegment` based URL filename guess: strips
     * the query string and fragment, takes the last path segment, and
     * percent-decodes it.
     */
    internal fun urlFileNameCandidate(url: String): String? {
        val noFragment = url.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val last = noQuery.substringAfterLast('/')
        if (last.isBlank() || last == "/") return null
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return decoded.takeIf { it.contains('.') }
    }

    /**
     * Parse a Content-Disposition header for the attachment filename,
     * preferring the RFC 6266 extended form (filename*=charset'lang'...)
     * over the plain form when both are present.
     */
    internal fun parseContentDispositionFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        CD_FILENAME_STAR_RE.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { raw ->
            val value = raw.trim().trim('"')
            val parts = value.split('\'', limit = 3)
            val encoded: String
            val charsetName: String
            if (parts.size == 3) {
                charsetName = parts[0].ifBlank { "UTF-8" }
                encoded = parts[2]
            } else {
                charsetName = "UTF-8"
                encoded = value
            }
            val decoded = runCatching { percentDecode(encoded, charsetName) }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        CD_FILENAME_RE.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { raw ->
            val value = raw.trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    /**
     * RFC 5987 percent-decoding: %XX escapes only. Unlike
     * [URLDecoder.decode] (application/x-www-form-urlencoded), a
     * literal '+' is left alone rather than turned into a space --
     * RFC 5987 extended values never use '+' for space.
     */
    internal fun percentDecode(s: String, charsetName: String): String {
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            // Non-ASCII chars here would be malformed per RFC 5987, but
            // fall back to UTF-8 byte-splatting rather than crashing.
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.write(b.toInt())
            i++
        }
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return String(bytes.toByteArray(), charset)
    }

    internal fun sanitize(name: String): String {
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

    internal fun uniqueFile(dir: File, fileName: String): File {
        var f = File(dir, fileName)
        if (!f.exists()) return f
        val ext = fileName.substringAfterLast('.', "")
        val stem = fileName.substringBeforeLast('.', fileName)
        var i = 1
        while (f.exists()) {
            f = File(dir, "$stem (" + i++ + ")" + if (ext.isEmpty()) "" else ".$ext")
        }
        return f
    }

    // -------------------------------------------------------------------------
    // Progress (pure / unit-testable -- see RedirectPolicyTest.kt)
    // -------------------------------------------------------------------------

    /**
     * Percent complete, clamped to [0, 100]. Returns -1 when the total
     * length is unknown (<= 0), so callers can distinguish "0%" from
     * "no progress bar possible" instead of silently drawing a bar that
     * can run past 100% when a server misreports Content-Length or a
     * chunked/declared length estimate is off.
     */
    internal fun clampProgressPercent(bytesWritten: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return -1
        if (bytesWritten <= 0L) return 0
        val pct = ((bytesWritten * 100L) / totalBytes).toInt()
        return pct.coerceIn(0, 100)
    }

    // -------------------------------------------------------------------------
    // Redirect / referrer policy (pure / unit-testable --
    // see RedirectPolicyTest.kt)
    // -------------------------------------------------------------------------

    internal enum class OriginRelation {
        /** Same host, no scheme downgrade: send the full referrer. */
        SAME_ORIGIN,
        /** Different host, no downgrade: send the origin only. */
        CROSS_SITE,
        /** Same host, https -> http: send no referrer. */
        SAME_HOST_DOWNGRADE,
        /** Different host AND https -> http: send no referrer. */
        DOWNGRADE_CROSS_SITE
    }

    internal fun hostOf(url: String): String? =
        runCatching { URL(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() }

    internal fun schemeOf(url: String): String =
        runCatching { URL(url).protocol }.getOrDefault("").lowercase()

    internal fun classifyRedirect(fromUrl: String, toUrl: String): OriginRelation {
        val fromHost = hostOf(fromUrl)
        val toHost = hostOf(toUrl)
        val sameHost = fromHost != null && fromHost == toHost
        val downgrade = schemeOf(fromUrl) == "https" && schemeOf(toUrl) == "http"
        return when {
            sameHost && !downgrade -> OriginRelation.SAME_ORIGIN
            sameHost && downgrade -> OriginRelation.SAME_HOST_DOWNGRADE
            !sameHost && downgrade -> OriginRelation.DOWNGRADE_CROSS_SITE
            else -> OriginRelation.CROSS_SITE
        }
    }

    /** scheme://host[:port] of [url], with no path/query/fragment. */
    internal fun originOf(url: String): String? = runCatching {
        val u = URL(url)
        val port = if (u.port != -1 && u.port != u.defaultPort) ":" + u.port else ""
        u.protocol + "://" + u.host + port
    }.getOrNull()

    /**
     * Referer to send for a request to [currentTargetUrl], given the
     * page URL ([referrerPageUrl]) that originally initiated the
     * download. Recomputed independently for every hop of a redirect
     * chain -- see the class doc.
     */
    internal fun refererForHop(referrerPageUrl: String?, currentTargetUrl: String): String? {
        if (referrerPageUrl.isNullOrBlank()) return null
        return when (classifyRedirect(referrerPageUrl, currentTargetUrl)) {
            OriginRelation.SAME_ORIGIN -> referrerPageUrl
            OriginRelation.CROSS_SITE -> originOf(referrerPageUrl)
            OriginRelation.SAME_HOST_DOWNGRADE,
            OriginRelation.DOWNGRADE_CROSS_SITE -> null
        }
    }

    // -------------------------------------------------------------------------
    // Toast helpers
    // -------------------------------------------------------------------------

    /** Call on the UI thread with a still-alive Activity (download start). */
    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Post a toast from any thread using an application context. Safe
     * even if the originating Activity has been destroyed meanwhile.
     */
    private fun toastOnUi(context: Context, msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }
}
