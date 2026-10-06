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
 * Flow for blob: URLs:
 *   1. install() attaches the bridge to the WebView (once per WebView,
 *      called from TabManager.buildWebView).
 *   2. start() posts an async JS snippet that fetches the blob URL,
 *      reads its ArrayBuffer, base64-encodes it, and calls
 *      window.MBBlobBridge.deliverData(b64, mime).
 *   3. deliverData() decodes the base64 on Dispatchers.IO and writes
 *      to Downloads via MediaStore (API 29+) or a direct File write
 *      (API 26-28).
 *   4. A Toast confirms the save, or reports failure.
 *
 * Flow for data: URLs:
 *   The payload is parsed inline -- no WebView involvement -- and
 *   written with the same helper.
 *
 * Size cap: 32 MB. The blob is materialised in the renderer as base64
 * (about 43 MB of string at the cap) and again as a ByteArray after
 * decode, so this bound keeps peak memory predictable. Real-world
 * blob downloads -- generated PDFs, canvas exports, CSV exports --
 * are almost always well under this.
 *
 * Note on concurrency: the object is a singleton, so only one blob
 * read is in flight at a time. Two tabs triggering a blob download
 * at the same instant is not a realistic scenario for a personal
 * browser, and the second call would simply overwrite the first's
 * callback slot.
 */
object BlobDownloadHelper {

    private const val TAG = "BlobDownload"

    /** Name the JS side calls into. Must match window.<NAME> in buildJs. */
    const val JS_NAME = "MBBlobBridge"

    private const val MAX_BLOB_BYTES = 32L * 1024L * 1024L   // 32 MB

    private data class Pending(
        val activity: Activity,
        val fileName: String,
        val fallbackMime: String?
    )

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
     * actual write happens asynchronously after the page hands back the
     * blob bytes.
     */
    fun start(
        activity: Activity,
        webView: WebView,
        blobUrl: String,
        fileName: String,
        mimeType: String?
    ) {
        if (blobUrl.isBlank()) return
        pending = Pending(activity, fileName.ifBlank { "download.bin" }, mimeType)

        val js = buildJs(blobUrl, MAX_BLOB_BYTES)
        try {
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed: " + t.message)
            pending = null
            toast(activity, activity.getString(R.string.download_failed))
        }
    }

    // ---------------------------------------------------------------------
    // Called from page JavaScript
    // ---------------------------------------------------------------------

    /**
     * Receives the base64-encoded blob bytes from the page. Runs on the
     * WebView's Java bridge thread -- not the UI thread -- so all UI
     * touches must be marshalled back.
     */
    @JavascriptInterface
    fun deliverData(b64: String, mimeType: String) {
        val p = pending ?: return
        pending = null

        if (b64.isEmpty()) {
            toastOnUi(p.activity, p.activity.getString(R.string.download_failed))
            return
        }

        val name = p.fileName
        val mime = p.fallbackMime?.takeIf { it.isNotBlank() }
            ?: mimeType.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        p.activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    writeToDownloads(p.activity.applicationContext, name, mime, bytes)
                } catch (t: Throwable) {
                    Log.w(TAG, "decode/write failed: " + t.message)
                    false
                }
            }
            val msg = if (ok) {
                p.activity.getString(R.string.download_saved, name)
            } else {
                p.activity.getString(R.string.download_failed)
            }
            toastOnUi(p.activity, msg)
        }
    }

    @JavascriptInterface
    fun deliverError(message: String) {
        val p = pending ?: return
        pending = null
        Log.w(TAG, "JS reported error: " + message)
        val msg = if (message == "too_big") {
            p.activity.getString(R.string.download_too_big)
        } else {
            p.activity.getString(R.string.download_failed)
        }
        toastOnUi(p.activity, msg)
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
            toast(activity, activity.getString(R.string.download_failed))
            return
        }
        val name = fileName.takeIf { it.isNotBlank() }
            ?: parsed.defaultName
        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: parsed.mime

        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeToDownloads(activity.applicationContext, name, mime, parsed.bytes)
            }
            val msg = if (ok) {
                activity.getString(R.string.download_saved, name)
            } else {
                activity.getString(R.string.download_failed)
            }
            toastOnUi(activity, msg)
        }
    }

    // ---------------------------------------------------------------------
    // JS builder
    // ---------------------------------------------------------------------

    /**
     * The read script. Runs as an async IIFE. Reads the blob via fetch,
     * encodes to base64 in 32 KB chunks (avoiding an Argument list
     * overflow in String.fromCharCode), then calls back into the Java
     * bridge.
     */
    private fun buildJs(blobUrl: String, maxBytes: Long): String {
        val urlLit = JSONObject.quote(blobUrl)
        return "(async function(){" +
            "try{" +
                "var r=await fetch(" + urlLit + ");" +
                "var b=await r.blob();" +
                "if(b.size>" + maxBytes + "){" +
                    "window." + JS_NAME + ".deliverError('too_big');return;" +
                "}" +
                "var ab=await b.arrayBuffer();" +
                "var bytes=new Uint8Array(ab);" +
                "var CHUNK=0x8000;" +
                "var parts=[];" +
                "for(var i=0;i<bytes.length;i+=CHUNK){" +
                    "parts.push(String.fromCharCode.apply(null,bytes.subarray(i,i+CHUNK)));" +
                "}" +
                "var b64=btoa(parts.join(''));" +
                "window." + JS_NAME + ".deliverData(b64,b.type||'');" +
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

    /**
     * Parse a data: URL of the form
     *   data:[<mime>][;base64],<payload>
     * Returns null on any malformed input.
     */
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
    // Disk write
    // ---------------------------------------------------------------------

    private fun writeToDownloads(
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
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return false

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
