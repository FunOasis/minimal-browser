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
 * Chrome parity notes:
 *   Chrome does not consult a hardcoded extension->MIME table. It lets
 *   the system MimeTypeMap decide from the file's extension, and only
 *   falls back to application/octet-stream for unknown types. It also
 *   does not set a description. Both are matched here.
 *
 *   Chrome prefers Environment.DIRECTORY_DOWNLOADS as the destination
 *   because that is where the user expects to find files. On some OEM
 *   ROMs (HyperOS/MIUI in particular) that call can reject with a
 *   SecurityException even on API 29+. When that happens we fall back
 *   to the app-specific external Downloads directory, which the user
 *   can reach via Android/data/com.minimalbrowser/files/Download/ and
 *   which the in-app Downloads screen picks up either way.
 *
 *   The toast on failure includes the exception class name so that a
 *   user without adb can still tell us what went wrong.
 *
 * Filename fidelity:
 *   URLUtil.guessFileName() is replaced with an explicit resolver that
 *   trusts the URL's own extension over the server Content-Type. Real
 *   servers routinely lie (README.md.txt, bundle.js.txt, clip.bin),
 *   which breaks every downstream tool that keys off the extension.
 *   Priority: URL extension > Content-Disposition extension > MIME.
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val PERMISSION_REQUEST_CODE = 4101
    private const val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val MAX_FILENAME_LENGTH = 200

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
     * "blob:https://example.com/7e9a..." -- no useful tail in the common
     * case. Try the MIME type first (most reliable), fall back to a
     * trailing extension embedded in the URL string, then to ".bin".
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
        val filename: String
        try {
            filename = guessFilename(url, contentDisposition, mimeType)
        } catch (t: Throwable) {
            Log.e(TAG, "filename resolution failed for " + url, t)
            toastWithReason(context, t)
            return
        }

        // Chrome-style MIME resolution: derive from the extension using
        // the system table. Only consult the server's hint (or default
        // to octet-stream) when the extension is unknown to the system.
        val ext = filename.substringAfterLast('.', "").lowercase()
        val chosenMime = when {
            ext.isNotBlank() ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: mimeType?.takeIf { it.isNotBlank() }
                    ?: FALLBACK_MIME
            !mimeType.isNullOrBlank() -> mimeType
            else -> FALLBACK_MIME
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setMimeType(chosenMime)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                // Public Downloads first -- that is where the user
                // expects to find the file. On OEM ROMs that reject the
                // call (SecurityException/IllegalArgumentException), fall
                // back to the app-specific external Downloads folder.
                var publicOk = false
                try {
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        filename
                    )
                    publicOk = true
                } catch (t: Throwable) {
                    Log.w(TAG, "public Downloads rejected: " +
                        t.javaClass.simpleName + ": " + t.message)
                }
                if (!publicOk) {
                    val dir = context.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    ) ?: throw IllegalStateException(
                        "external files dir unavailable"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    setDestinationUri(Uri.fromFile(File(dir, filename)))
                    Log.i(TAG, "using app-specific dir: " + dir)
                }
            }

            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)
            }
            if (!userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            Log.i(TAG, "enqueue ok id=" + id +
                " name=" + filename +
                " mime=" + chosenMime +
                " url=" + url)
            toast(context, context.getString(R.string.download_started, filename))
        } catch (t: Throwable) {
            Log.e(TAG, "enqueue failed url=" + url +
                " name=" + filename +
                " mime=" + chosenMime, t)
            toastWithReason(context, t)
        }
    }

    /**
     * Show the failure toast with the exception class appended. Without
     * adb this is the fastest way for the user to tell us whether the
     * destination, the MIME type, or the URI parser is at fault.
     */
    private fun toastWithReason(context: Context, t: Throwable) {
        val base = context.getString(R.string.download_failed)
        val reason = t.javaClass.simpleName
        toast(context, base + ": " + reason)
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
