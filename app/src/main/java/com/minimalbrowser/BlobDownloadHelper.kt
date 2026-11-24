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
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Base64

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
 * Concurrency:
 *   Every download owns a unique token and its own Pending record in a
 *   token-keyed map, so unrelated downloads never interfere with each
 *   other. An earlier version additionally cleared EVERY other pending
 *   download each time start() ran, which meant starting a second blob
 *   download silently cancelled the first -- concurrent or rapidly
 *   triggered downloads were simply not possible. That blanket-clear is
 *   gone; a soft cap (MAX_CONCURRENT_DOWNLOADS) and a per-download
 *   watchdog timeout (PENDING_TIMEOUT_MS) take its place, so a page
 *   that never calls back (navigated away, revoked blob, crashed
 *   renderer) cannot leak an open output stream forever, and a
 *   pathological page cannot open unbounded concurrent downloads.
 *
 * Truncation:
 *   The Blob's reported size (begin()'s totalBytes, from JS `b.size`)
 *   is authoritative -- unlike an HTTP Content-Length it is never
 *   "unknown". deliverChunk() now checks, on the final chunk, that the
 *   bytes actually written match that size; a short transfer (a
 *   JS-bridge hiccup, a renderer interruption mid-stream) is reported
 *   as a failure instead of a silently truncated "successful" file.
 *
 * Threading / lifecycle:
 *   Bridge callbacks arrive on the WebView's JavaBridge thread and can
 *   outlive the Activity (chunked transfer of a large canvas export
 *   while the user navigates away). Pending therefore stores ONLY the
 *   application context -- a strong Activity reference held for the
 *   whole transfer was a leak that survived until the next blob
 *   download started.
 *
 * Diagnostic toasts carry a suffix identifying the failing stage:
 *     ": openOutput ...", ": js-eval", ": blob-not-captured ...",
 *     ": write", ": decode", ": truncated", ": too-many-active",
 *     ": <msg from page>", ": data-parse", ": data-write". A bare
 *     "Download failed" means an older build.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    const val JS_NAME = "MBBlobBridge"

    private const val CHUNK_SIZE = 1 * 1024 * 1024
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    /**
     * Hard ceiling on simultaneously in-flight blob/data downloads.
     * Each holds one open OutputStream (and, pre-Q, one open FD); this
     * bounds how much a single page -- malicious or merely buggy -- can
     * make the browser hold open at once. Comfortably above anything a
     * real multi-file "download all" interaction would trigger.
     */
    private const val MAX_CONCURRENT_DOWNLOADS = 16

    /**
     * If a started download's token never reaches begin()/deliverChunk
     * .../deliverError within this long, it is assumed abandoned (page
     * navigated away before the async reader resolved, the blob was
     * revoked before the reader ran, the renderer crashed) and is swept
     * so its destination is cleaned up instead of leaking indefinitely.
     */
    private const val PENDING_TIMEOUT_MS = 5 * 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())

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
        val token: String,
        val appContext: Context,
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

    // Token-keyed so a stale reader script from an abandoned download can
    // never write its bytes into a newer download's stream, and so
    // unrelated concurrent downloads never interfere with each other.
    private val pendings =
        java.util.concurrent.ConcurrentHashMap<String, Pending>()

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

        if (pendings.size >= MAX_CONCURRENT_DOWNLOADS) {
            Log.w(TAG, "refusing new blob download: " + pendings.size + " already in flight")
            toast(activity, activity.getString(R.string.download_failed) + ": too-many-active")
            return
        }

        val token = java.util.UUID.randomUUID().toString()
        val p = Pending(
            token = token,
            appContext = activity.applicationContext,
            fileName = fileName.ifBlank {
                "download_" + System.currentTimeMillis() + ".bin"
            },
            mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        )
        pendings[token] = p

        // Watchdog: if this token never completes (page never calls
        // begin/deliverChunk/deliverError), reclaim it instead of
        // leaking an open destination forever. A download that is
        // legitimately still transferring is unaffected -- this only
        // fires if `token` is STILL mapped to this exact Pending when
        // the timeout runs.
        mainHandler.postDelayed({
            if (pendings.remove(token, p)) {
                Log.w(TAG, "blob download timed out, abandoning token=" + token)
                abandon(p)
            }
        }, PENDING_TIMEOUT_MS)

        val js = buildReaderJs(blobUrl, token)
        try {
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "reader posted token=" + token)
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed: " + t.message)
            pendings.remove(token, p)
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
    fun begin(token: String, suggestedName: String, totalBytes: Long) {
        val p = pendings[token] ?: return
        if (p.opened || p.failed) return

        val clean = sanitize(suggestedName)
        if (clean.isNotBlank()) p.fileName = clean
        p.totalBytes = totalBytes

        val err = openOutput(p)
        if (err != null) {
            Log.w(TAG, "openOutput failed: " + err)
            p.failed = true
            pendings.remove(token, p)
            toastOnUi(p.appContext, p.appContext.getString(R.string.download_failed) +
                ": openOutput " + err)
            return
        }
        p.opened = true
        Log.i(TAG, "begin name=" + p.fileName + " total=" + totalBytes)
        DownloadNotifications.start(p.appContext, p.fileName, totalBytes)
    }

    @JavascriptInterface
    fun deliverChunk(token: String, b64: String, isLast: Boolean) {
        val p = pendings[token] ?: return
        if (p.failed || !p.opened) return

        if (b64.isNotEmpty()) {
            try {
                val bytes = Base64.getDecoder().decode(b64)
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
                    pendings.remove(token, p)
                    toastOnUi(p.appContext,
                        p.appContext.getString(R.string.download_failed) + ": write")
                    return
                }

                // Throttle notification updates to roughly 1% granularity.
                val pct = DownloadHandler.clampProgressPercent(p.bytesWritten, p.totalBytes)
                if (pct >= 0 && pct != p.lastNotifPct) {
                    p.lastNotifPct = pct
                    DownloadNotifications.progress(
                        p.appContext, p.fileName, p.bytesWritten, p.totalBytes
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "chunk decode failed: " + t.message)
                p.failed = true
                finalizeDownload(p, success = false)
                pendings.remove(token, p)
                toastOnUi(p.appContext,
                    p.appContext.getString(R.string.download_failed) + ": decode")
                return
            }
        }

        if (isLast) {
            // The Blob's own reported size is authoritative (unlike an
            // HTTP Content-Length it is never "unknown"), so a mismatch
            // here means bytes were lost somewhere between the page and
            // disk -- a JS-bridge interruption or a partial chunk --
            // and the file must NOT be reported as a successful download.
            val truncated = p.totalBytes > 0L && p.bytesWritten != p.totalBytes
            if (truncated) {
                Log.w(TAG, "blob transfer truncated: got " + p.bytesWritten +
                    " of " + p.totalBytes + " bytes")
                p.failed = true
                finalizeDownload(p, success = false)
                pendings.remove(token, p)
                DownloadNotifications.complete(p.appContext, p.fileName, success = false)
                toastOnUi(p.appContext,
                    p.appContext.getString(R.string.download_failed) + ": truncated")
                return
            }

            Log.i(TAG, "complete, bytes=" + p.bytesWritten)
            finalizeDownload(p, success = true)
            pendings.remove(token, p)
            DownloadNotifications.complete(p.appContext, p.fileName, success = true)
            toastOnUi(
                p.appContext,
                p.appContext.getString(R.string.download_saved, p.fileName)
            )
        }
    }

    @JavascriptInterface
    fun deliverError(token: String, message: String) {
        val p = pendings[token] ?: return
        p.failed = true
        val wasOpened = p.opened
        finalizeDownload(p, success = false)
        pendings.remove(token, p)
        Log.w(TAG, "JS reported error: " + message)
        if (wasOpened) {
            DownloadNotifications.complete(p.appContext, p.fileName, success = false)
        }
        val short = if (message.length > 80) message.substring(0, 80) else message
        toastOnUi(p.appContext, p.appContext.getString(R.string.download_failed) +
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
        val app = activity.applicationContext
        val header = parseDataUrlHeader(dataUrl)
        if (header == null) {
            toast(activity, activity.getString(R.string.download_failed) + ": data-parse")
            return
        }
        val name = fileName.takeIf { it.isNotBlank() } ?: header.defaultName
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: header.mime

        // Base64 expands the payload by ~4/3, so the decoded size is
        // roughly 3/4 of the encoded payload length. This is only an
        // estimate (padding/whitespace throw it off slightly) used for
        // the progress bar, which is clamped to [0, 100] regardless.
        val estimatedTotal = if (header.isBase64) {
            (header.payload.length.toLong() * 3L) / 4L
        } else {
            header.payload.length.toLong()
        }

        DownloadNotifications.start(app, name, estimatedTotal)

        CoroutineScope(Dispatchers.IO).launch {
            val (ok, uri, bytesWritten) = writeDataUrlStreaming(app, name, mime, header, estimatedTotal)
            if (ok) {
                uri?.let {
                    LocalDownloadsStore.get(app).record(name, it, mime, bytesWritten)
                }
                DownloadNotifications.complete(app, name, success = true)
                toastOnUi(app, app.getString(R.string.download_saved, name))
            } else {
                DownloadNotifications.complete(app, name, success = false)
                toastOnUi(app, app.getString(R.string.download_failed) + ": data-write")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reader script
    // ---------------------------------------------------------------------

    private fun buildReaderJs(blobUrl: String, token: String): String {
        val urlLit = JSONObject.quote(blobUrl)
        val tokLit = JSONObject.quote(token)
        return "(async function(){" +
            "try{" +
                "var url=" + urlLit + ";" +
                "var tok=" + tokLit + ";" +
                "var store=window.__mbBlobs||{};" +
                "var names=window.__mbNames||{};" +
                "var b=store[url];" +
                "if(!b){" +
                    "window." + JS_NAME + ".deliverError(tok,'blob-not-captured keys='+Object.keys(store).length);" +
                    "return;" +
                "}" +
                "var name=names[url]||'';" +
                "var total=b.size||0;" +
                "window." + JS_NAME + ".begin(tok,name,total);" +
                "var ab=await b.arrayBuffer();" +
                "var bytes=new Uint8Array(ab);" +
                "var n=bytes.length;" +
                "if(n===0){window." + JS_NAME + ".deliverChunk(tok,'',true);return;}" +
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
                    "window." + JS_NAME + ".deliverChunk(tok,b64,(end===n));" +
                "}" +
            "}catch(e){" +
                "window." + JS_NAME + ".deliverError(tok,String(e&&e.message||e));" +
            "}" +
        "})();"
    }

    // ---------------------------------------------------------------------
    // data: parsing (pure / unit-testable -- see BlobDownloadHelperTest.kt)
    // ---------------------------------------------------------------------

    internal data class DataUrlHeader(
        val mime: String,
        val isBase64: Boolean,
        val payload: String,
        val defaultName: String
    )

    /**
     * Parse a data: URL's header (mime type, base64-ness) and split off
     * its payload WITHOUT decoding any bytes. Decoding happens later,
     * streamed straight to the destination in [writeDataUrlStreaming],
     * so a large data: URL (e.g. a multi-megabyte canvas export) never
     * needs a second, fully-materialised decoded ByteArray held in
     * memory alongside the original encoded string.
     */
    internal fun parseDataUrlHeader(url: String): DataUrlHeader? {
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

        return DataUrlHeader(mime, isBase64, payload, defaultName)
    }

    internal fun mimeToExt(mime: String): String = when (mime.lowercase()) {
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

    /**
     * Decode and write a data: URL's payload straight to the
     * destination in bounded-size chunks, rather than decoding the
     * entire payload into one ByteArray up front. For the base64 case
     * this is a real memory-footprint win on large data URLs: the old
     * implementation held the encoded string (from the WebView
     * callback), then additionally allocated a fully decoded ByteArray
     * of comparable size, before a single bulk write -- roughly 2x the
     * payload size in extra heap at peak, on top of the page's own copy.
     */
    private fun writeDataUrlStreaming(
        context: Context,
        fileName: String,
        mime: String,
        header: DataUrlHeader,
        estimatedTotal: Long
    ): Triple<Boolean, String?, Long> {
        var output: OutputStream? = null
        var mediaUri: Uri? = null
        var fileTarget: File? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        val os = resolver.openOutputStream(uri)
                        if (os != null) {
                            output = os
                            mediaUri = uri
                        } else {
                            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaStore threw: " + t.message)
                }
            }

            if (output == null) {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return Triple(false, null, 0L)
                if (!dir.exists() && !dir.mkdirs()) return Triple(false, null, 0L)
                // Uniquify on the app-specific-storage fallback path, the
                // same way the blob path's openOutput() already does --
                // without this, two data: URL downloads sharing a
                // filename (or a download reusing an earlier file's
                // name) silently truncated whatever was already there.
                val f = DownloadHandler.uniqueFile(dir, fileName)
                output = FileOutputStream(f)
                fileTarget = f
            }

            val out = output
            var bytesWritten = 0L
            var lastPct = -1

            if (header.isBase64) {
                val src = ByteArrayInputStream(header.payload.toByteArray(Charsets.US_ASCII))
                // The MIME decoder tolerates embedded whitespace/line
                // breaks in the payload, matching how lenient the old
                // android.util.Base64 decoder was for this input.
                val decoded = Base64.getMimeDecoder().wrap(src)
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val n = decoded.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    bytesWritten += n
                    val pct = DownloadHandler.clampProgressPercent(bytesWritten, estimatedTotal)
                    if (pct >= 0 && pct != lastPct) {
                        lastPct = pct
                        DownloadNotifications.progress(context, fileName, bytesWritten, estimatedTotal)
                    }
                }
            } else {
                // Percent-encoded text payloads (data:text/plain,Hello)
                // are practically always small -- unlike base64
                // image/blob exports -- so a single decode is fine here.
                val bytes = Uri.decode(header.payload).toByteArray(Charsets.UTF_8)
                out.write(bytes)
                bytesWritten = bytes.size.toLong()
            }
            out.flush()

            if (mediaUri != null) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(mediaUri, values, null, null)
                return Triple(true, mediaUri.toString(), bytesWritten)
            }
            if (fileTarget != null) {
                return Triple(true, Uri.fromFile(fileTarget).toString(), bytesWritten)
            }
            return Triple(false, null, bytesWritten)
        } catch (t: Throwable) {
            Log.w(TAG, "data: write failed: " + t.message)
            if (mediaUri != null) {
                try { context.contentResolver.delete(mediaUri, null, null) } catch (_: Throwable) {}
            }
            fileTarget?.let { try { it.delete() } catch (_: Throwable) {} }
            return Triple(false, null, 0L)
        } finally {
            try { output?.close() } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------------
    // Destination (blob chunked path)
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
            val f = DownloadHandler.uniqueFile(dir, p.fileName)
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
    // Helpers
    // ---------------------------------------------------------------------

    internal fun sanitize(name: String): String {
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

    /** Post a toast from any thread using an application context. */
    private fun toastOnUi(context: Context, msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    /** Call on the UI thread with a still-alive Activity. */
    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
