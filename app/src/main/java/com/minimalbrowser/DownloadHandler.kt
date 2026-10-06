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
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.net.URLDecoder

/**
 * Bridges WebView's DownloadListener to Android's system DownloadManager,
 * with a JS-mediated fallback for blob: and data: URLs.
 *
 * Routing:
 *   http://, https://          -> DownloadManager (existing path)
 *   blob:                      -> BlobDownloadHelper (JS-mediated)
 *   data:                      -> BlobDownloadHelper (inline decode)
 *   anything else              -> reject with a toast
 *
 * Filename fidelity (unchanged from before):
 *   URLUtil.guessFileName() is replaced with an explicit resolver
 *   because it trusts the server's Content-Type over the URL's own
 *   extension. Real-world servers routinely lie, producing names like
 *   README.md.txt, bundle.js.txt, clip.bin, which break every
 *   downstream tool that keys off the extension.
 *
 *   Priority: URL extension > Content-Disposition extension >
 *   MIME-derived extension.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val PERMISSION_REQUEST_CODE = 4101
    private const val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val MAX_FILENAME_LENGTH = 200

    private val MIME_OVERRIDES = mapOf(
        "md"       to "text/markdown",
        "markdown" to "text/markdown",
        "js"       to "application/javascript",
        "mjs"      to "application/javascript",
        "jsx"      to "text/jsx",
        "ts"       to "application/typescript",
        "tsx"      to "application/typescript",
        "kt"       to "text/x-kotlin",
        "kts"      to "text/x-kotlin",
        "py"       to "text/x-python",
        "rb"       to "text/x-ruby",
        "rs"       to "text/x-rust",
        "go"       to "text/x-go",
        "toml"     to "application/toml",
        "yaml"     to "application/yaml",
        "yml"      to "application/yaml",
    )

    private val EXTENDED_FILENAME_RE =
        Regex("""filename\*\s*=\s*[^']*''([^;\r\n]+)""", RegexOption.IGNORE_CASE)

    private val CLASSIC_FILENAME_RE =
        Regex("""filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE)

    @Volatile
    private var pending: PendingDownload? = null

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long
    )

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /**
     * Handle a download request from WebView.
     *
     * The WebView is required for blob: URLs -- the JS read must run in
     * the same document that created the blob. For http(s) it is unused
     * and may be null in tests.
     */
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

        when (scheme) {
            "blob" -> {
                if (webView == null) {
                    toast(activity, activity.getString(R.string.download_failed))
                    return
                }
                val name = blobFallbackName(url, mimeType)
                BlobDownloadHelper.start(
                    activity = activity,
                    webView = webView,
                    blobUrl = url,
                    fileName = name,
                    mimeType = mimeType
                )
                return
            }
            "data" -> {
                val name = dataFallbackName(mimeType)
                BlobDownloadHelper.startDataUrl(
                    activity = activity,
                    dataUrl = url,
                    fileName = name,
                    mimeType = mimeType
                )
                return
            }
            "http", "https" -> {
                // fall through to the DownloadManager path below
            }
            else -> {
                Log.w(TAG, "Unsupported download scheme: " + scheme)
                toast(activity, activity.getString(R.string.download_unsupported))
                return
            }
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

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun needsStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return context.checkSelfPermission(STORAGE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Filename for a blob: URL. The blob URL itself looks like
     * "blob:https://example.com/7e9a..." -- no useful tail in the
     * common case. We try the MIME type first (the most reliable
     * source), fall back to a filename extension embedded anywhere in
     * the URL string (some pages build blob URLs with a trailing
     * ".ext" for convenience), and only then default to ".bin".
     */
    private fun blobFallbackName(url: String, mimeType: String?): String {
        val fromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }

        val fromUrl = url.substringAfterLast('.', "")
            .takeIf { ext ->
                ext.isNotEmpty() &&
                ext.length <= 8 &&
                ext.all { it.isLetterOrDigit() }
            }

        val ext = fromMime ?: fromUrl ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
    }

    private fun dataFallbackName(mimeType: String?): String {
        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
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

            val ext = filename.substringAfterLast('.', "").lowercase()
            val chosenMime =
                if (ext.isBlank()) mimeType ?: FALLBACK_MIME
                else mimeForExtension(ext, mimeType)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription(url)
                setMimeType(chosenMime)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )

                // Prefer the public Downloads directory so the file is
                // reachable from a file manager and the system Downloads
                // app. On a handful of OEM ROMs and some scoped-storage
                // configurations, this call can throw because the app
                // cannot mkdir the target directory. Rather than let the
                // whole download fail, fall back to the app-specific
                // external Downloads folder, which is still visible to
                // the user under Android/data/<pkg>/files/Download/.
                var destinationSet = false
                try {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        filename
                    )
                    destinationSet = true
                } catch (t: Throwable) {
                    Log.w(TAG, "public Downloads destination rejected: " +
                        t.javaClass.simpleName + ": " + t.message)
                }
                if (!destinationSet) {
                    try {
                        val dir = context.getExternalFilesDir(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        if (dir != null) {
                            if (!dir.exists()) dir.mkdirs()
                            val target = File(dir, filename)
                            setDestinationUri(Uri.fromFile(target))
                            Log.i(TAG, "using app-specific destination: " + target)
                        } else {
                            throw IllegalStateException("no external files dir")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "fallback destination failed too: " +
                            t.javaClass.simpleName + ": " + t.message)
                        throw t
                    }
                }

                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

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
            // Log the concrete exception type too. "Download failed"
            // alone is useless for diagnosing a user report; the class
            // name and message pinpoint the exact call that blew up.
            Log.w(TAG, "enqueue failed for " + url + ": " +
                t.javaClass.simpleName + ": " + t.message)
            toast(context, context.getString(R.string.download_failed))
        }
    }

    private fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val urlName = lastPathSegment(url)
        val urlExt  = urlName?.fileExtension()

        val cdName  = parseContentDisposition(contentDisposition)
        val cdExt   = cdName?.fileExtension()

        if (urlName != null && urlExt != null) {
            if (cdName != null) {
                val cdStem = cdName.substringBeforeLast('.', cdName).trim()
                if (cdStem.isNotBlank()) return sanitize(cdStem + "." + urlExt)
            }
            return sanitize(urlName)
        }

        if (cdName != null && cdExt != null) return sanitize(cdName)
        if (urlName != null) return sanitize(urlName)
        if (cdName != null) return sanitize(cdName)

        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "download_" + System.currentTimeMillis() + "." + ext
    }

    private fun mimeForExtension(ext: String, serverMime: String?): String {
        MIME_OVERRIDES[ext]?.let { return it }
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return serverMime?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME
    }

    private fun lastPathSegment(url: String): String? = runCatching {
        Uri.parse(url).lastPathSegment
    }.getOrNull()
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.takeIf { it.isNotBlank() && it != "/" }

    private fun String.fileExtension(): String? {
        val dot = lastIndexOf('.')
        if (dot <= 0 || dot == length - 1) return null
        val ext = substring(dot + 1).lowercase()
        if (ext.length > 10) return null
        if (!ext.all { it.isLetterOrDigit() }) return null
        if (ext.all { it.isDigit() }) return null
        return ext
    }

    private fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        val extended = EXTENDED_FILENAME_RE.find(header)
            ?.groupValues?.getOrNull(1)?.trim()

        val classic = CLASSIC_FILENAME_RE.find(header)?.let { m ->
            m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: m.groupValues.getOrNull(2)
        }?.trim()

        var name = extended?.takeIf { it.isNotBlank() } ?: classic ?: return null
        if (name.isBlank()) return null

        name = name.substringAfterLast('/').substringAfterLast('\\')

        if (name.contains('%')) {
            name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        }

        return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun sanitize(name: String): String {
        var cleaned = name
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .trimEnd('.')

        if (cleaned.length > MAX_FILENAME_LENGTH) {
            val ext = cleaned.substringAfterLast('.', "")
            val stem = cleaned.substringBeforeLast('.', cleaned)
            val keep = (MAX_FILENAME_LENGTH - (ext.length + 1)).coerceAtLeast(1)
            cleaned = stem.take(keep) + if (ext.isBlank()) "" else "." + ext
        }

        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "download"
        return cleaned
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
