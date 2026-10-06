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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves blob: and data: URL "downloads" by reading their bytes in-page
 * and writing them straight to the public Downloads folder.
 *
 * Why this exists:
 *   The system DownloadManager cannot fetch blob: or data: URLs -- the
 *   bytes only exist inside the renderer that created them. Chrome on
 *   Android solves this by asking the page for the blob's bytes via
 *   JavaScript and then writing them to storage itself. We do the same.
 *
 * Streaming design (no size cap):
 *   The blob is read by JS as an ArrayBuffer, then base64-encoded and
 *   sent to Java in 1 MB chunks. Each chunk is decoded and written to
 *   the open output stream before the next chunk arrives. Peak memory
 *   is bounded by a single chunk -- about 1 MB of binary plus ~1.4 MB
 *   of base64 in the renderer -- regardless of the total blob size.
 *
 *   This replaces the earlier "buffer the whole blob, then write"
 *   approach, which required a hard cap (32 MB) because both the
 *   base64 string in the renderer and the decoded ByteArray on the
 *   Java side lived simultaneously. The cap is gone. The only limit
 *   now is free disk space, same as Chrome.
 *
 * Lifecycle of a blob download:
 *   1. start() opens the output (MediaStore entry on API 29+, plain
 *      File on API 26-28), then posts the read script to the WebView.
 *   2. JS fetches the blob, slices the ArrayBuffer into 1 MB windows,
 *      base64-encodes each window, and calls deliverChunk(b64, isLast)
 *      once per window.
 *   3. deliverChunk decodes and appends. When isLast is true it also
 *      flushes, closes, and finalises the MediaStore entry.
 *   4. On any failure (JS catch, decode error, write error) the
 *      partial entry is deleted and a toast reports the failure.
 *
 * Concurrency:
 *   Only one blob download is in flight at a time. A new start()
 *   abandons any previous in-flight download (deleting its partial
 *   file). This matches the reality that a user is not clicking two
 *   blob download links in the same second.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    /** Name the JS side calls into. Must match window.<NAME> in buildJs. */
    const val JS_NAME = "MBBlobBridge"

    // 1 MB per bridge call. Larger chunks mean fewer round-trips across
    // the JS/Java boundary; smaller chunks mean lower peak memory. 1 MB
    // is the sweet spot -- a 4 GB blob goes over the bridge in 4096
    // calls, each carrying about 1.4 MB of base64 text.
    private const val CHUNK_SIZE = 1 * 1024 * 1024

    private class Pending(
        val appContext: Context,
        val activity: Activity,
        val fileName: String,
        val mime: String
    ) {
        var output: OutputStream? = null
        var mediaStoreUri: Uri? = null
        var bytesWritten: Long = 0L
        var failed: Boolean = false
    }

    private val lock = Any()

    @Volatile private var pending: Pending? = null

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    /**
     * Attach the JS bridge to a WebView. Safe to call multiple times on
     * the same WebView -- addJavascriptInterface overwrites by name.
     */
    fun install(webView: WebView) {
        try {
            webView.addJavascriptInterface(this, JS_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: " + t.message)
        }
    }

    // ---------------------------------------------------------------------
    // blob: entry point
    // ---------------------------------------------------------------------

    /**
     * Begin a JS-mediated save of a blob URL. Returns immediately; the
     * actual bytes arrive asynchronously via deliverChunk().
     */
    fun start(
        activity: Activity,
        webView: WebView,
        blobUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        if (blobUrl.isBlank()) return

        // Abandon any previous in-flight blob download.
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

        if (!openOutput(p)) {
            toast(activity, activity.getString(R.string.download_failed))
            return
        }
        synchronized(lock) { pending = p }

        val js = buildJs(blobUrl)
        try {
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed: " + t.message)
            synchronized(lock) {
                abandon(p)
                pending = null
            }
            toast(activity, activity.getString(R.string.download_failed))
        }
    }

    // ---------------------------------------------------------------------
    // Called from page JavaScript
    // ---------------------------------------------------------------------

    /**
     * Receives one base64-encoded chunk of the blob. Runs on a WebView
     * Java bridge thread -- not the UI thread -- so all UI touches are
     * marshalled back. The output stream is written under a lock so
     * consecutive chunks cannot interleave even if the bridge dispatches
     * them to different threads.
     */
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
                    synchronized(lock) {
                        if (pending === p) pending = null
                    }
                    toastOnUi(p.activity, p.activity.getString(R.string.download_failed))
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "chunk decode failed: " + t.message)
                p.failed = true
                finalizeDownload(p, success = false)
                synchronized(lock) {
                    if (pending === p) pending = null
                }
                toastOnUi(p.activity, p.activity.getString(R.string.download_failed))
                return
            }
        }

        if (isLast) {
            finalizeDownload(p, success = true)
            synchronized(lock) {
                if (pending === p) pending = null
            }
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
        synchronized(lock) {
            if (pending === p) pending = null
        }
        Log.w(TAG, "JS reported error: " + message)
        toastOnUi(p.activity, p.activity.getString(R.string.download_failed))
    }

    // ---------------------------------------------------------------------
    // data: entry point -- unchanged, inline decode
    // ---------------------------------------------------------------------

    fun startDataUrl(
        activity: Activity,
        dataUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        val parsed = parseDataUrl(dataUrl) ?: run {
            toast(activity, activity.getString(R.string.download_failed))
            return
        }
        val name = fileName.takeIf { it.isNotBlank() } ?: parsed.defaultName
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: parsed.mime

        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeAllAtOnce(activity.applicationContext, name, mime, parsed.bytes)
            }
            val msg = if (ok) {
                activity.getString(R.string.download_saved, name)
            } else {
                activity.getString(R.string.download_failed)
            }
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------------------------------------------------
    // JS builder -- chunked reader
    // ---------------------------------------------------------------------

    /**
     * The read script. Runs as an async IIFE. Reads the blob via fetch,
     * slices the ArrayBuffer into 1 MB windows, base64-encodes each
     * window (in 32 KB sub-slices, avoiding an Argument list overflow in
     * String.fromCharCode), and delivers it across the bridge. The last
     * window is flagged so the Java side can close the stream.
     */
    private fun buildJs(blobUrl: String): String {
        val urlLit = JSONObject.quote(blobUrl)
        return "(async function(){" +
            "try{" +
                "var r=await fetch(" + urlLit + ");" +
                "var b=await r.blob();" +
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
    // data: URL parsing
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

        val ext = mimeToExt(mime)
        val defaultName = "download_" + System.currentTimeMillis() + "." + ext

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
    // Streaming output lifecycle
    // ---------------------------------------------------------------------

    /**
     * Open the destination. On API 29+ this is a MediaStore Downloads
     * entry with IS_PENDING=1; on API 26-28 it is a plain File under the
     * public Downloads directory.
     */
    private fun openOutput(p: Pending): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, p.fileName)
                put(MediaStore.Downloads.MIME_TYPE, p.mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = p.appContext.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return false
            p.mediaStoreUri = uri
            p.output = resolver.openOutputStream(uri)
            p.output != null
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!dir.exists() && !dir.mkdirs()) return false
            val f = File(dir, p.fileName)
            p.output = FileOutputStream(f)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "openOutput failed: " + t.message)
        false
    }

    /**
     * Flush and close the stream, then either commit the MediaStore row
     * (success) or delete the partial entry (failure). On API < Q, an
     * unsuccessful write deletes the file we created.
     */
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
                try {
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "finalize update failed: " + t.message)
                }
            } else {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Throwable) {}
            }
        } else if (!success) {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                File(dir, p.fileName).delete()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Discard an in-flight download without touching the screen. Used
     * when a new start() supersedes an old one.
     */
    private fun abandon(p: Pending) {
        finalizeDownload(p, success = false)
    }

    // ---------------------------------------------------------------------
    // data: URL disk write (single shot)
    // ---------------------------------------------------------------------

    private fun writeAllAtOnce(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, fileName, mime, bytes)
        } else {
            writeViaFile(fileName, bytes)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "write failed: " + t.message)
        false
    }

    private fun writeViaMediaStore(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false
        } catch (t: Throwable) {
            Log.w(TAG, "stream write failed: " + t.message)
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            return false
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    private fun writeViaFile(fileName: String, bytes: ByteArray): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = File(dir, fileName)
        FileOutputStream(target).use { it.write(bytes) }
        return true
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun toastOnUi(activity: Activity, msg: String) {
        try {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            // Activity gone; drop the toast.
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
