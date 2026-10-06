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
 * and writing them straight to disk.
 *
 * Why blob capture, not fetch:
 *   The naive approach -- take the blob URL we receive from the page,
 *   then call fetch(blobUrl) from injected JS -- fails on any page that
 *   revokes the object URL the moment it triggers the download. GitHub
 *   does exactly that. The synthetic click, the requestFullscreen, the
 *   revokeObjectURL call: all of them run in the same JS task, before
 *   our evaluateJavascript post ever reaches the page. By the time we
 *   try to fetch, the URL is dead and the renderer throws "Failed to
 *   fetch".
 *
 *   Chrome avoids this because the Blob is captured synchronously, in
 *   the same tick as createObjectURL. We do the same thing: inject a
 *   small page-start script that wraps URL.createObjectURL, and keep a
 *   strong reference to every Blob the page makes, keyed by URL string.
 *   When our DownloadListener fires with a blob: URL, we look up the
 *   Blob in that map and read its bytes directly. No fetch, no timing
 *   race, no revocation window.
 *
 * Lifecycle:
 *   1. injectCapture() runs at onPageStarted, before any page script.
 *   2. The page calls URL.createObjectURL(blob) at some later point;
 *      our wrapper records it in window.__mbBlobs.
 *   3. User taps download. WebView fires DownloadListener. We call
 *      start() with the blob: URL.
 *   4. start() reads window.__mbBlobs[url], calls blob.arrayBuffer(),
 *      and pushes the bytes across the bridge in 1 MB chunks.
 *   5. deliverChunk() writes each chunk and finalises on isLast=true.
 *
 * Storage strategy:
 *   API 29+: MediaStore.Downloads first, fall back to app-specific
 *   external Downloads on failure.
 *   API 26-28: app-specific external Downloads. No runtime permission.
 *
 * Diagnostic toasts -- every failure path identifies itself:
 *     ": openOutput ..."    destination creation failed
 *     ": js-eval"           WebView refused to run the reader script
 *     ": blob-not-captured" the URL is not in __mbBlobs
 *     ": write"             chunk write failed
 *     ": decode"            base64 decode failed
 *     ": js <msg>"          page JS threw, msg carries the reason
 *     ": data-parse"        data: URL malformed
 *     ": data-write"        data: URL parsed, write failed
 *   A bare "Download failed" means an older build.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    const val JS_NAME = "MBBlobBridge"

    private const val CHUNK_SIZE = 1 * 1024 * 1024

    /**
     * Page-start capture script. Idempotent via the __mbBlobs marker.
     *
     * We intentionally do NOT prevent the real revokeObjectURL from
     * running -- the browser's own mapping is freed as soon as the page
     * asks for it, which is what the page wants. Our captured Blob
     * object lives independently: revoking the URL does not invalidate
     * the Blob, and holding a strong reference to it keeps its backing
     * bytes alive long enough for us to read them.
     *
     * The delayed delete of our own map entry is a small safety net.
     * Sixty seconds is far more than any real download flow needs, and
     * the map is capped at whatever the page creates in that window.
     */
    val CAPTURE_JS: String =
        "(function(){" +
            "if(window.__mbBlobs)return;" +
            "window.__mbBlobs={};" +
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
        "})();"

    private class Pending(
        val appContext: Context,
        val activity: Activity,
        val fileName: String,
        val mime: String
    ) {
        var output: OutputStream? = null
        var mediaStoreUri: Uri? = null
        var fileTarget: File? = null
        var bytesWritten: Long = 0L
        var failed: Boolean = false
    }

    private val lock = Any()

    @Volatile private var pending: Pending? = null

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    /**
     * Attach the JS bridge. Safe to call multiple times.
     * The capture script is NOT installed here -- it must run in every
     * new document, so it goes through injectCapture() from the
     * WebViewClient's onPageStarted hook.
     */
    fun install(webView: WebView) {
        try {
            webView.addJavascriptInterface(this, JS_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: " + t.message)
        }
    }

    /**
     * Inject the capture hook into the current document. Called from
     * BlockingWebViewClient.onPageStarted so it runs before any page
     * script -- that is what lets us intercept every createObjectURL.
     * Skips internal pages, matching the pattern used by PageScrollProbe
     * and CosmeticFilter.
     */
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
    // blob: entry point
    // ---------------------------------------------------------------------

    fun start(
        activity: Activity,
        webView: WebView,
        blobUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        Log.i(TAG, "start url=" + blobUrl + " name=" + fileName + " mime=" + mimeType)
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

        val openError = openOutput(p)
        if (openError != null) {
            Log.w(TAG, "openOutput failed: " + openError)
            toast(activity, activity.getString(R.string.download_failed) +
                ": openOutput " + openError)
            return
        }
        synchronized(lock) { pending = p }

        val js = buildJs(blobUrl)
        try {
            webView.evaluateJavascript(js, null)
            Log.i(TAG, "reader posted")
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed: " + t.message)
            synchronized(lock) {
                abandon(p)
                pending = null
            }
            toast(activity, activity.getString(R.string.download_failed) + ": js-eval")
        }
    }

    // ---------------------------------------------------------------------
    // Bridge callbacks from the reader script
    // ---------------------------------------------------------------------

    @JavascriptInterface
    fun deliverChunk(b64: String, isLast: Boolean) {
        val p = pending ?: return
        if (p.failed) return

        if (b64.isNotEmpty()) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                var writeFailed = false
                var writeError: String? = null

                synchronized(p) {
                    val out = p.output
                    if (out == null) {
                        writeFailed = true
                        writeError = "output already closed"
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
        finalizeDownload(p, success = false)
        synchronized(lock) { if (pending === p) pending = null }
        Log.w(TAG, "JS reported error: " + message)
        val short = if (message.length > 80) message.substring(0, 80) else message
        toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
            ": " + short)
    }

    // ---------------------------------------------------------------------
    // data: URL
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

        CoroutineScope(Dispatchers.IO).launch {
            val ok = writeAllAtOnce(
                activity.applicationContext, name, mime, parsed.bytes
            )
            val msg = if (ok) {
                activity.getString(R.string.download_saved, name)
            } else {
                activity.getString(R.string.download_failed) + ": data-write"
            }
            try {
                activity.runOnUiThread {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------------
    // Reader script -- reads the CAPTURED Blob, not the URL
    // ---------------------------------------------------------------------

    private fun buildJs(blobUrl: String): String {
        val urlLit = JSONObject.quote(blobUrl)
        return "(async function(){" +
            "try{" +
                "var store=window.__mbBlobs||{};" +
                "var b=store[" + urlLit + "];" +
                "if(!b){" +
                    "window." + JS_NAME + ".deliverError('blob-not-captured keys='+Object.keys(store).length);" +
                    "return;" +
                "}" +
                "var ab=await b.arrayBuffer();" +
                "var bytes=new Uint8Array(ab);" +
                "var total=bytes.length;" +
                "if(total===0){" +
                    "window." + JS_NAME + ".deliverChunk('',true);" +
                    "return;" +
                "}" +
                "var CHUNK=" + CHUNK_SIZE + ";" +
                "var STRSZ=32768;" +
                "for(var off=0;off<total;off+=CHUNK){" +
                    "var end=Math.min(off+CHUNK,total);" +
                    "var sub=bytes.subarray(off,end);" +
                    "var parts=[];" +
                    "for(var i=0;i<sub.length;i+=STRSZ){" +
                        "parts.push(String.fromCharCode.apply(null,sub.subarray(i,i+STRSZ)));" +
                    "}" +
                    "var b64=btoa(parts.join(''));" +
                    "var isLast=(end===total);" +
                    "window." + JS_NAME + ".deliverChunk(b64,isLast);" +
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
                        Log.i(TAG, "destination: MediaStore")
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
            } else {
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            }
        } else if (!success) {
            try { p.fileTarget?.delete() } catch (_: Throwable) {}
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
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, fileName, mime, bytes)
        } else {
            writeViaFile(context, fileName, bytes)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "write failed: " + t.message)
        false
    }

    private fun writeViaMediaStore(
        context: Context, fileName: String, mime: String, bytes: ByteArray
    ): Boolean {
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
                    return true
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
    ): Boolean = try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "file write failed: " + t.message)
        false
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun toastOnUi(activity: Activity, msg: String) {
        try {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
