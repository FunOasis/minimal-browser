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
import android.widget.Toast
import java.net.URLDecoder

/**
 * Bridges WebView's DownloadListener to Android's system DownloadManager.
 *
 * ─── Filename fidelity ────────────────────────────────────────────────
 * This class replaces URLUtil.guessFileName() with an explicit resolver
 * because the framework helper trusts the server's Content-Type over the
 * URL's own extension. Real-world servers routinely lie:
 *
 *   • GitHub raw URLs serve  README.md  as  text/plain
 *   • Many CDNs serve        bundle.js  as  text/plain
 *   • Video hosts often send             clip.mp4  as application/octet-stream
 *
 * That produces downloads named  README.md.txt , bundle.js.txt , clip.bin ,
 * which breaks every downstream tool that keys off the extension.
 *
 * Our priority is strict: URL extension > Content-Disposition extension >
 * MIME-derived extension. The chosen extension is then also used to pick
 * the MIME type we hand to DownloadManager, so MediaStore never gets a
 * chance to re-classify the file behind our back.
 * ──────────────────────────────────────────────────────────────────────
 */
object DownloadHandler {

    private const val TAG = "DownloadHandler"
    private const val PERMISSION_REQUEST_CODE = 4101
    private const val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val MAX_FILENAME_LENGTH = 200

    /**
     * Android's MimeTypeMap is missing several common developer-file
     * extensions. Without these overrides, a .md file with no Content-Type
     * hint on the server would fall through to octet-stream.
     */
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

    private fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val filename = guessFilename(url, contentDisposition, mimeType)

            // Derive the MIME type we hand to DownloadManager from the
            // filename's extension — never from the server. That's what
            // stops MediaStore from second-guessing the extension on
            // API 29+ scoped storage.
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
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    filename
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            // Preserve session cookies and User-Agent so auth-gated
            // downloads still work.
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
     * Resolve the on-disk filename.
     *
     * Priority, highest first:
     *   1. URL extension + Content-Disposition stem (best of both worlds)
     *   2. URL path segment, if it has an extension
     *   3. Content-Disposition filename, if it has an extension
     *   4. URL path segment, even without an extension
     *   5. Content-Disposition filename, even without an extension
     *   6. Synthesized "download_<ts>.<ext>" from the MIME type
     */
    private fun guessFilename(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val urlName = lastPathSegment(url)
        val urlExt  = urlName?.fileExtension()

        val cdName  = parseContentDisposition(contentDisposition)
        val cdExt   = cdName?.fileExtension()

        // Case 1 — URL has an extension. It wins.
        if (urlName != null && urlExt != null) {
            if (cdName != null) {
                // Prefer the Content-Disposition stem when it exists (it's
                // usually cleaner than a URL with cache-busting hashes) but
                // keep the URL's extension verbatim.
                val cdStem = cdName.substringBeforeLast('.', cdName).trim()
                if (cdStem.isNotBlank()) return sanitize("$cdStem.$urlExt")
            }
            return sanitize(urlName)
        }

        // Case 2 — URL has no extension, but Content-Disposition does.
        if (cdName != null && cdExt != null) return sanitize(cdName)

        // Case 3 — URL name exists without an extension.
        if (urlName != null) return sanitize(urlName)

        // Case 4 — Content-Disposition name exists without an extension.
        if (cdName != null) return sanitize(cdName)

        // Case 5 — Nothing usable. Synthesize from MIME + timestamp.
        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
        return "download_${System.currentTimeMillis()}.$ext"
    }

    private fun mimeForExtension(ext: String, serverMime: String?): String {
        MIME_OVERRIDES[ext]?.let { return it }
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        // Server MIME is a last resort. If it disagrees with the extension,
        // we still prefer to publish the extension-derived one — but if
        // neither map knows the extension, the server is our only clue.
        return serverMime?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME
    }

    /**
     * Return the last path segment of the URL, stripping query and fragment.
     * Returns null if the URL ends in "/" or has no path segment.
     */
    private fun lastPathSegment(url: String): String? = runCatching {
        Uri.parse(url).lastPathSegment
    }.getOrNull()
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.takeIf { it.isNotBlank() && it != "/" }

    /**
     * Extract a lowercase extension from a filename, or null if there isn't
     * a sensible one. Rejects "v1.2.3" (numeric tail) and other false
     * positives that would poison the URL-priority rule.
     */
    private fun String.fileExtension(): String? {
        val dot = lastIndexOf('.')
        if (dot <= 0 || dot == length - 1) return null
        val ext = substring(dot + 1).lowercase()
        if (ext.length > 10) return null
        if (!ext.all { it.isLetterOrDigit() }) return null
        if (ext.all { it.isDigit() }) return null
        return ext
    }

    /**
     * Parse a Content-Disposition header into a filename. Handles both
     * the classic quoted form and the RFC 5987 extended form used for
     * non-ASCII names:
     *
     *   attachment; filename="Annual Report 2026.pdf"
     *   attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf
     */
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

        // Strip any path separators that snuck in.
        name = name.substringAfterLast('/').substringAfterLast('\\')

        // Decode percent escapes (RFC 5987 or a sloppy server).
        if (name.contains('%')) {
            name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        }

        return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    /**
     * Make a filename safe for DownloadManager without ever changing its
     * extension. Slashes become underscores, trailing dots/whitespace are
     * trimmed, and the total length is capped at 200 chars while keeping
     * the extension intact.
     */
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
            cleaned = stem.take(keep) + if (ext.isBlank()) "" else ".$ext"
        }

        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "download"
        return cleaned
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
