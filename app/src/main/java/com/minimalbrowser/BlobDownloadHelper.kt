package com.minimalbrowser

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves blob: and data: URL downloads by reading their bytes in-page
 * and writing them straight to disk, with a status-bar notification and
 * a local record so the file also shows up in the app's Downloads
 * screen.
 *
 * Filename capture:
 *   A blob: URL is opaque -- "blob:https://site/uuid" carries no
 *   filename. The name comes from the anchor element the page uses to
 *   trigger the download: <a download="File.java" href="blob:...">.
 *   We hook HTMLAnchorElement.prototype.click in the page to record
 *   href -> download pairs, then look the pair up when the download
 *   reaches us.
 *
 * Blob capture, not fetch:
 *   Pages routinely revokeObjectURL the same tick they click, so a
 *   later fetch(blobUrl) fails with "Failed to fetch". We instead hook
 *   URL.createObjectURL and keep a strong reference to every Blob the
 *   page makes, keyed by URL. Revoking the URL does not invalidate the
 *   Blob itself.
 *
 * Two-phase start:
 *   start()  -- creates a pending record and posts the reader script.
 *   begin()  -- called by the page once it has the filename and size;
 *               opens the destination and posts the notification.
 *   deliverChunk() -- one call per 1 MB window; writes bytes.
 *   finalizeDownload() on isLast=true.
 *
 * Diagnostic toasts carry a suffix identifying the failing stage:
 *     ": openOutput ...", ": js-eval", ": blob-not-captured ...",
 *     ": write", ": decode", ": <msg from page>", ": data-parse",
 *     ": data-write". A bare "Download failed" means an older build.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    const val JS_NAME = "MBBlobBridge"

    private const val CHUNK_SIZE = 1 * 1024 * 1024
    private const val NAME_MAP_TTL_MS = 60_000L

    /**
     * Page-start capture hook. Idempotent via window markers.
     *
     * Three jobs:
     *   1. Wrap URL.createObjectURL so every Blob is retained in
     *      window.__mbBlobs, keyed by URL.
     *   2. Wrap URL.revokeObjectURL so the browser's own mapping is
     *      freed (as the page intends) while our retained reference
     *      survives -- we delete it from __mbBlobs after a delay.
     *   3. Wrap HTMLAnchorElement.prototype.click to record
     *      href -> download pairs whenever a blob-backed anchor is
     *      clicked. This is where the real filename comes from.
     */
    val CAPTURE_JS: String =
        "(function(){" +
            "if(window.__mbCaptureInstalled)return;" +
            "window.__mbCaptureInstalled=true;" +
            "window.__mbBlobs={};" +
            "window.__mbNames={};" +

            "var origCreate=URL.createObjectURL.bind(URL);" +
            "URL.createObjectURL=function(b){" +
                "var u=origCreate(b);" +
                "try{window.__mbBlobs[u]=b;}catch(e){}" +
                "return u;" +
            "};" +

            "var origRevoke=URL.revokeObjectURL.bind(URL);" +
            "URL.revokeObjectURL=function(u){" +
                "try{origRevoke(u);}catch(e){}" +
                "setTimeout(function(){" +
                    "try{delete window.__mbBlobs[u];}catch(e){}" +
                "},60000);" +
            "};" +

            "try{" +
                "var origClick=HTMLAnchorElement.prototype.click;" +
                "HTMLAnchorElement.prototype.click=function(){" +
                    "try{" +
                        "var h=this.href||'';" +
                        "var d=this.download||'';" +
                        "if(h.indexOf('blob:')===0&&d){" +
                            "window.__mbNames[h]=d;" +
                        "}" +
                    "}catch(e){}" +
                    "return origClick.apply(this,arguments);" +
                "};" +
            "}catch(e){}" +
        "})();"

    private class Pending(
        val appContext: Context,
        val activity: Activity,
        var fileName: String,
        val mime: String
    ) {
        var output: OutputStream? = null
        var mediaStoreUri: Uri? = null
        var fileTarget: File? = null
        var bytesWritten: Long = 0L
        var totalBytes: Long = 0L
        var failed: Boolean = false
        var opened: Boolean = false
        var lastNotifPct: Int = -1
    }

    private val lock = Any()

    @Volatile private var pending: Pending? = null

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    fun install(webView: WebView) {
        try {
            webView.addJavascriptInterface(this, JS_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: " + t.message)
        }
    }

    fun injectCapture(webView: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (url.startsWith("data:")) return
        if (url.startsWith("about:")) return
        try {
            webView.evaluateJavascript(CAPTURE_JS, null)
        } catch (t: Throwable) {
            Log.w(TAG, "injectCapture failed: " + t.message)
        }
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    fun start(
        activity: Activity,
        webView: WebView,
        blobUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        Log.i(TAG, "start url=" + blobUrl + " fallback=" + fileName + " mime=" + mimeType)
        if (blobUrl.isBlank()) return

        synchronized(lock) {
            pending?.let { abandon(it) }
            pending = null
        }

        val p = Pending(
            appContext = activity.applicationContext,
            activity = activity,
            fileName = fileName.ifBlank {
                "download_" + System.currentTimeMillis() + ".bin"
            },
            mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        )
        synchronized(lock) { pending = p }

        val js = buildReaderJs(blobUrl)
        try {
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "reader posted")
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed: " + t.message)
            synchronized(lock) { if (pending === p) pending = null }
            toast(activity, activity.getString(R.string.download_failed) + ": js-eval")
        }
    }

    // ---------------------------------------------------------------------
    // Bridge callbacks
    // ---------------------------------------------------------------------

    /**
     * Called by the reader script once it has the page-side filename
     * (from __mbNames) and total size. This is the first point where we
     * can open the destination with the correct name.
     *
     * Runs on the WebView bridge thread. Synchronous from JS's point of
     * view: JS blocks here until we return.
     */
    @JavascriptInterface
    fun begin(suggestedName: String, totalBytes: Long) {
        val p = pending ?: return
        if (p.opened || p.failed) return

        val clean = sanitize(suggestedName)
        if (clean.isNotBlank()) p.fileName = clean
        p.totalBytes = totalBytes

        val err = openOutput(p)
        if (err != null) {
            Log.w(TAG, "openOutput failed: " + err)
            p.failed = true
            synchronized(lock) { if (pending === p) pending = null }
            toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
                ": openOutput " + err)
            return
        }
        p.opened = true
        Log.i(TAG, "begin name=" + p.fileName + " total=" + totalBytes)
        DownloadNotifications.start(p.activity, p.fileName, totalBytes)
    }

    @JavascriptInterface
    fun deliverChunk(b64: String, isLast: Boolean) {
        val p = pending ?: return
        if (p.failed || !p.opened) return

        if (b64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                var writeFailed = false
                var writeError: String? = null

                synchronized(p) {
                    val out = p.output
                    if (out == null) {
                        writeFailed = true
                        writeError = "output closed"
                    } else {
                        try {
                            out.write(bytes)
                            p.bytesWritten += bytes.size
                        } catch (t: Throwable) {
                            writeFailed = true
                            writeError = t.message
                        }
                    }
                }

                if (writeFailed) {
                    Log.w(TAG, "chunk write failed: " + writeError)
                    p.failed = true
                    finalizeDownload(p, success = false)
                    synchronized(lock) { if (pending === p) pending = null }
                    toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
                        ": write")
                    return
                }

                // Throttle notification updates to 1% granularity.
                if (p.totalBytes > 0L) {
                    val pct = ((p.bytesWritten * 100L) / p.totalBytes).toInt()
                    if (pct != p.lastNotifPct) {
                        p.lastNotifPct = pct
                        DownloadNotifications.progress(
                            p.activity, p.fileName, p.bytesWritten, p.totalBytes
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "chunk decode failed: " + t.message)
                p.failed = true
                finalizeDownload(p, success = false)
                synchronized(lock) { if (pending === p) pending = null }
                toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
                    ": decode")
                return
            }
        }

        if (isLast) {
            Log.i(TAG, "complete, bytes=" + p.bytesWritten)
            finalizeDownload(p, success = true)
            synchronized(lock) { if (pending === p) pending = null }
            DownloadNotifications.complete(p.activity, p.fileName, success = true)
            toastOnUi(
                p.activity,
                p.activity.getString(R.string.download_saved, p.fileName)
            )
        }
    }

    @JavascriptInterface
    fun deliverError(message: String) {
        val p = pending ?: return
        p.failed = true
        val wasOpened = p.opened
        finalizeDownload(p, success = false)
        synchronized(lock) { if (pending === p) pending = null }
        Log.w(TAG, "JS reported error: " + message)
        if (wasOpened) DownloadNotifications.complete(p.activity, p.fileName, success = false)
        val short = if (message.length > 80) message.substring(0, 80) else message
        toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
            ": " + short)
    }

    // ---------------------------------------------------------------------
    // data: URL entry point
    // ---------------------------------------------------------------------

    fun startDataUrl(
        activity: Activity,
        dataUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        val parsed = parseDataUrl(dataUrl) ?: run {
            toast(activity, activity.getString(R.string.download_failed) + ": data-parse")
            return
        }
        val name = fileName.takeIf { it.isNotBlank() } ?: parsed.defaultName
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: parsed.mime

        DownloadNotifications.start(activity, name, parsed.bytes.size.toLong())

        CoroutineScope(Dispatchers.IO).launch {
            val result = writeAllAtOnce(
                activity.applicationContext, name, mime, parsed.bytes
            )
            val ok = result.first
            val uri = result.second
            if (ok) {
                uri?.let {
                    LocalDownloadsStore.get(activity).record(
                        name, it, mime, parsed.bytes.size.toLong()
                    )
                }
                DownloadNotifications.complete(activity, name, success = true)
                uiToast(activity, activity.getString(R.string.download_saved, name))
            } else {
                DownloadNotifications.complete(activity, name, success = false)
                uiToast(
                    activity,
                    activity.getString(R.string.download_failed) + ": data-write"
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reader script
    // ---------------------------------------------------------------------

    private fun buildReaderJs(blobUrl: String): String {
        val urlLit = JSONObject.quote(blobUrl)
        return "(async function(){" +
            "try{" +
                "var url=" + urlLit + ";" +
                "var store=window.__mbBlobs||{};" +
                "var names=window.__mbNames||{};" +
                "var b=store[url];" +
                "if(!b){" +
                    "window." + JS_NAME + ".deliverError('blob-not-captured keys='+Object.keys(store).length);" +
                    "return;" +
                "}" +
                "var name=names[url]||'';" +
                "var total=b.size||0;" +
                "window." + JS_NAME + ".begin(name,total);" +
                "var ab=await b.arrayBuffer();" +
                "var bytes=new Uint8Array(ab);" +
                "var n=bytes.length;" +
                "if(n===0){window." + JS_NAME + ".deliverChunk('',true);return;}" +
                "var CHUNK=" + CHUNK_SIZE + ";" +
                "var STRSZ=32768;" +
                "for(var off=0;off<n;off+=CHUNK){" +
                    "var end=Math.min(off+CHUNK,n);" +
                    "var sub=bytes.subarray(off,end);" +
                    "var parts=[];" +
                    "for(var i=0;i<sub.length;i+=STRSZ){" +
                        "parts.push(String.fromCharCode.apply(null,sub.subarray(i,i+STRSZ)));" +
                    "}" +
                    "var b64=btoa(parts.join(''));" +
                    "window." + JS_NAME + ".deliverChunk(b64,(end===n));" +
                "}" +
            "}catch(e){" +
                "window." + JS_NAME + ".deliverError(String(e&&e.message||e));" +
            "}" +
        "})();"
    }

    // ---------------------------------------------------------------------
    // data: parsing
    // ---------------------------------------------------------------------

    private data class DataPayload(
        val mime: String,
        val bytes: ByteArray,
        val defaultName: String
    )

    private fun parseDataUrl(url: String): DataPayload? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null

        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val isBase64 = header.endsWith(";base64", ignoreCase = true)
        val mimePart = if (isBase64) header.substring(0, header.length - 7) else header
        val mime = mimePart.takeIf { it.isNotBlank() }
            ?.substringBefore(';')
            ?: "application/octet-stream"
        val defaultName = "download_" + System.currentTimeMillis() + "." + mimeToExt(mime)

        return try {
            val bytes = if (isBase64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            DataPayload(mime, bytes, defaultName)
        } catch (t: Throwable) {
            Log.w(TAG, "data: parse failed: " + t.message)
            null
        }
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "application/pdf" -> "pdf"
        "application/zip" -> "zip"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "application/octet-stream" -> "bin"
        else -> mime.substringAfterLast('/', "bin").take(8)
    }

    // ---------------------------------------------------------------------
    // Destination
    // ---------------------------------------------------------------------

    private fun openOutput(p: Pending): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, p.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, p.mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = p.appContext.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    val os = resolver.openOutputStream(uri)
                    if (os != null) {
                        p.mediaStoreUri = uri
                        p.output = os
                        Log.i(TAG, "destination: MediaStore " + uri)
                        return null
                    }
                    Log.w(TAG, "MediaStore openOutputStream null")
                    try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                } else {
                    Log.w(TAG, "MediaStore insert null")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore threw: " +
                    t.javaClass.simpleName + ": " + t.message)
            }
        }

        return try {
            val dir = p.appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return "no-ext-dir"
            if (!dir.exists() && !dir.mkdirs()) return "mkdir"
            val f = File(dir, p.fileName)
            p.output = FileOutputStream(f)
            p.fileTarget = f
            Log.i(TAG, "destination: app dir " + f.absolutePath)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "app dir threw: " +
                t.javaClass.simpleName + ": " + t.message)
            t.javaClass.simpleName
        }
    }

    private fun finalizeDownload(p: Pending, success: Boolean) {
        synchronized(p) {
            try { p.output?.flush() } catch (_: Throwable) {}
            try { p.output?.close() } catch (_: Throwable) {}
            p.output = null
        }

        val uri = p.mediaStoreUri
        if (uri != null) {
            val resolver = p.appContext.contentResolver
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                try { resolver.update(uri, values, null, null) }
                catch (t: Throwable) { Log.w(TAG, "finalize update: " + t.message) }
                LocalDownloadsStore.get(p.appContext).record(
                    p.fileName, uri.toString(), p.mime, p.bytesWritten
                )
            } else {
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            }
        } else {
            val f = p.fileTarget
            if (f != null) {
                if (success) {
                    LocalDownloadsStore.get(p.appContext).record(
                        p.fileName,
                        Uri.fromFile(f).toString(),
                        p.mime,
                        p.bytesWritten
                    )
                } else {
                    try { f.delete() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun abandon(p: Pending) {
        finalizeDownload(p, success = false)
    }

    // ---------------------------------------------------------------------
    // data: disk write
    // ---------------------------------------------------------------------

    private fun writeAllAtOnce(
        context: Context, fileName: String, mime: String, bytes: ByteArray
    ): Pair<Boolean, String?> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, fileName, mime, bytes)
        } else {
            writeViaFile(context, fileName, bytes)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "write failed: " + t.message)
        Pair(false, null)
    }

    private fun writeViaMediaStore(
        context: Context, fileName: String, mime: String, bytes: ByteArray
    ): Pair<Boolean, String?> {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val out = resolver.openOutputStream(uri)
                if (out != null) {
                    out.use { it.write(bytes); it.flush() }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return Pair(true, uri.toString())
                }
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore write failed: " + t.message)
        }
        return writeViaFile(context, fileName, bytes)
    }

    private fun writeViaFile(
        context: Context, fileName: String, bytes: ByteArray
    ): Pair<Boolean, String?> {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return Pair(false, null)
            if (!dir.exists() && !dir.mkdirs()) return Pair(false, null)
            val f = File(dir, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            Pair(true, Uri.fromFile(f).toString())
        } catch (t: Throwable) {
            Log.w(TAG, "file write failed: " + t.message)
            Pair(false, null)
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun sanitize(name: String): String {
        var s = name.replace('/', '_').replace('\\', '_').trim().trimEnd('.')
        if (s.isBlank() || s == "." || s == "..") return ""
        if (s.length > 180) {
            val ext = s.substringAfterLast('.', "")
            val stem = s.substringBeforeLast('.', s)
            val keep = (180 - (ext.length + 1)).coerceAtLeast(1)
            s = stem.take(keep) + if (ext.isBlank()) "" else "." + ext
        }
        return s
    }

    private fun uiToast(activity: Activity, msg: String) {
        try {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun toastOnUi(activity: Activity, msg: String) {
        uiToast(activity, msg)
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
