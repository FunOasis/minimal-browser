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
 * Why this exists:
 *   The system DownloadManager cannot fetch blob: URLs. The bytes only
 *   exist inside the renderer. GitHub's "Download raw file" button uses
 *   fetch + Blob + URL.createObjectURL, so its download arrives here as
 *   a blob: URL -- not as an http(s) URL. Chrome on Android solves this
 *   the same way: ask the page for the bytes via JS, then write them
 *   itself.
 *
 * Storage strategy:
 *   On API 29+, try MediaStore.Downloads first (public Downloads
 *   folder, no permission needed). If MediaStore rejects the insert --
 *   which happens on some ROMs for unfamiliar file extensions -- fall
 *   back to the app-specific external Downloads directory.
 *
 *   On API 26-28, skip MediaStore entirely and write to the app-specific
 *   external Downloads directory. This avoids needing WRITE_EXTERNAL_
 *   STORAGE at runtime, which the in-process download path (in
 *   DownloadHandler) no longer requests.
 *
 * Diagnostic toasts:
 *   Every failure now carries a suffix identifying which stage failed:
 *     ": openOutput"       -- could not create the destination file
 *     ": js-eval"          -- WebView refused to run the reader script
 *     ": write"            -- chunk write to the output stream failed
 *     ": decode"           -- base64 decode failed
 *     ": js <msg>"         -- page JS threw, msg carries the reason
 *     ": data-parse"       -- data: URL could not be parsed
 *     ": data-write"       -- data: URL parse succeeded but write failed
 *   A plain "Download failed" with no colon means an older build.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    const val JS_NAME = "MBBlobBridge"

    private const val CHUNK_SIZE = 1 * 1024 * 1024

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
            Log.i(TAG, "js posted to webview")
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
    // Called from page JavaScript
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
                    synchronized(lock) {
                        if (pending === p) pending = null
                    }
                    toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
                        ": write")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "chunk decode failed: " + t.message)
                p.failed = true
                finalizeDownload(p, success = false)
                synchronized(lock) {
                    if (pending === p) pending = null
                }
                toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
                    ": decode")
                return
            }
        }

        if (isLast) {
            Log.i(TAG, "complete, bytes=" + p.bytesWritten)
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
        val short = if (message.length > 80) message.substring(0, 80) else message
        toastOnUi(p.activity, p.activity.getString(R.string.download_failed) +
            ": js " + short)
    }

    // ---------------------------------------------------------------------
    // data: entry point
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
                activity.applicationContext,
                name,
                mime,
                parsed.bytes
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
            } catch (_: Throwable) {
                // Activity gone; drop the toast.
            }
        }
    }

    // ---------------------------------------------------------------------
    // JS builder
    // ---------------------------------------------------------------------

    private fun buildJs(blobUrl: String): String {
        val urlLit = JSONObject.quote(blobUrl)
        return "(async function(){" +
            "try{" +
                "var r=await fetch(" + urlLit + ");" +
                "if(!r.ok){window." + JS_NAME + ".deliverError('HTTP '+r.status);return;}" +
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
    // Destination
    // ---------------------------------------------------------------------

    /**
     * Open the destination. Returns null on success, or a short
     * diagnostic string on failure.
     *
     * Strategy:
     *   1. API 29+: try MediaStore.Downloads with IS_PENDING=1.
     *   2. If that fails (or API < 29), use app-specific external
     *      Downloads. No permission needed, visible to file managers
     *      under Android/data/com.minimalbrowser/files/Download/.
     */
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
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )
                if (uri != null) {
                    val os = resolver.openOutputStream(uri)
                    if (os != null) {
                        p.mediaStoreUri = uri
                        p.output = os
                        Log.i(TAG, "destination: MediaStore")
                        return null
                    }
                    Log.w(TAG, "MediaStore openOutputStream returned null")
                    try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                } else {
                    Log.w(TAG, "MediaStore insert returned null")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore path threw: " +
                    t.javaClass.simpleName + ": " + t.message)
            }
        }

        // Fallback: app-specific external Downloads.
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
            Log.w(TAG, "app dir path threw: " +
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
                try {
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "finalize update failed: " + t.message)
                }
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
    // data: URL disk write
    // ---------------------------------------------------------------------

    private fun writeAllAtOnce(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, fileName, mime, bytes)
            } else {
                writeViaFile(context, fileName, bytes)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: " + t.message)
            false
        }
    }

    private fun writeViaMediaStore(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
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
                    out.use {
                        it.write(bytes)
                        it.flush()
                    }
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
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): Boolean {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val target = File(dir, fileName)
            FileOutputStream(target).use { it.write(bytes) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "file write failed: " + t.message)
            false
        }
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
