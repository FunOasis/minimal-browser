package com.minimalbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Warehouse for cosmetic-rule subscriptions.
 *
 * Structurally similar to BlocklistStore but with three deliberate
 * differences:
 *
 *  1. Different storage directory (filesDir/cosmetics/), so a future
 *     change to one warehouse format cannot corrupt the other.
 *  2. No serialized hosts cache. Cosmetic lists are only read at boot
 *     and at refresh, not on every navigation, so the parse cost of
 *     the raw ZIP contents is paid once per app start.
 *  3. Longer freshness window (72h vs 24h). EasyList cosmetic
 *     sections change slowly; hitting easylist.to every day is
 *     needless load on a volunteer-run server.
 *
 * Raw rule text is stored as-is inside a single-entry ZIP. Parsing
 * (line-by-line EasyList format) happens in CosmeticRules.parse on
 * first use after boot, keeping this class free of any knowledge
 * about rule grammar.
 */
class CosmeticStore private constructor(private val appContext: Context) {

    data class Entry(
        val url: String,
        val fileName: String,
        val byteSize: Long,
        val lastFetched: Long,
        val lastAttemptAt: Long,
        val ok: Boolean,
        val error: String?
    )

    private val dir: File = File(appContext.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }
    private val manifestFile: File = File(dir, MANIFEST_NAME)

    companion object {
        private const val TAG = "CosmeticStore"
        private const val DIR_NAME = "cosmetics"
        private const val MANIFEST_NAME = "manifest.json"
        private const val DATA_ENTRY_NAME = "rules.txt"
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "MinimalBrowser/1.0"
        private const val REFRESH_INTERVAL_MS = 72L * 60L * 60L * 1000L
        private const val RETRY_INTERVAL_MS = 60L * 60L * 1000L
        private const val MAX_BYTES = 12L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val READ_CHUNK = 64 * 1024

        @Volatile private var inst: CosmeticStore? = null

        fun get(ctx: Context): CosmeticStore =
            inst ?: synchronized(this) {
                inst ?: CosmeticStore(ctx.applicationContext).also { inst = it }
            }
    }

    @Synchronized
    fun listAll(): List<Entry> {
        val arr = readManifest()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = entryFromJson(o) ?: continue
            if (!File(dir, e.fileName).exists()) continue
            out.add(e)
        }
        return out
    }

    @Synchronized
    fun remove(url: String) {
        val arr = readManifest()
        val out = JSONArray()
        var removedFile: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url", "") == url) {
                removedFile = o.optString("fileName", "")
            } else {
                out.put(o)
            }
        }
        writeManifest(out)
        removedFile?.takeIf { it.isNotBlank() }?.let { File(dir, it).delete() }
    }

    suspend fun addAndFetch(url: String): Entry = withContext(Dispatchers.IO) {
        val clean = url.trim()
        val existing = listAll().firstOrNull { it.url == clean }
        val fileName = existing?.fileName ?: ("list_" + shortHash(clean) + ".zip")
        val now = System.currentTimeMillis()

        try {
            val text = download(clean)
            val target = File(dir, fileName)
            writeZip(target, text)
            val entry = Entry(
                url = clean,
                fileName = fileName,
                byteSize = target.length(),
                lastFetched = now,
                lastAttemptAt = now,
                ok = true,
                error = null
            )
            upsertManifest(entry)
            Log.i(TAG, "Fetched " + clean + " (" + target.length() + " bytes)")
            entry
        } catch (t: Throwable) {
            Log.w(TAG, "Fetch failed for " + clean + ": " + t.message)
            val previous = existing ?: Entry(
                url = clean,
                fileName = fileName,
                byteSize = 0L,
                lastFetched = 0L,
                lastAttemptAt = 0L,
                ok = false,
                error = null
            )
            val stillGood = previous.lastFetched > 0L &&
                File(dir, previous.fileName).exists()
            val updated = previous.copy(
                lastAttemptAt = now,
                ok = stillGood,
                error = t.message ?: "unknown"
            )
            upsertManifest(updated)
            updated
        }
    }

    suspend fun refreshAll(force: Boolean): Int = withContext(Dispatchers.IO) {
        val entries = listAll()
        var refreshed = 0
        val now = System.currentTimeMillis()
        for (e in entries) {
            val fresh = e.ok && (now - e.lastFetched < REFRESH_INTERVAL_MS)
            val recentlyTried = (now - e.lastAttemptAt) < RETRY_INTERVAL_MS
            if (!force && (fresh || recentlyTried)) continue
            val updated = addAndFetch(e.url)
            if (updated.ok) refreshed++
        }
        refreshed
    }

    /**
     * Concatenate the raw text of every OK list. Called once per boot
     * by CosmeticFilter to rebuild the parsed rule set. Empty string
     * if no lists have been fetched yet.
     */
    suspend fun loadAllRaw(): String = withContext(Dispatchers.IO) {
        val entries = listAll().filter { it.ok }
        if (entries.isEmpty()) return@withContext ""

        val sb = StringBuilder(4 * 1024 * 1024)
        for (e in entries) {
            try {
                val text = readZip(File(dir, e.fileName))
                if (text.isNotEmpty()) {
                    sb.append(text)
                    if (!text.endsWith('\n')) sb.append('\n')
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Read failed for " + e.fileName + ": " + t.message)
            }
        }
        sb.toString()
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    private fun writeZip(target: File, text: String) {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry(DATA_ENTRY_NAME))
            zos.write(text.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    private fun readZip(source: File): String {
        ZipInputStream(source.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == DATA_ENTRY_NAME) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        return ""
    }

    // ------------------------------------------------------------------
    // HTTP with manual redirects, gzip decode and a size cap
    // ------------------------------------------------------------------

    private fun download(url: String): String {
        var current = url
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                when {
                    code in 200..299 -> {
                        return readCapped(
                            conn.inputStream,
                            conn.contentEncoding,
                            MAX_BYTES
                        )
                    }
                    code == 301 || code == 302 || code == 303 ||
                    code == 307 || code == 308 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw RuntimeException("HTTP " + code + " without Location")
                        current = URL(URL(current), location).toString()
                        hops++
                    }
                    else -> throw RuntimeException("HTTP " + code)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw RuntimeException("Too many redirects (>" + MAX_REDIRECTS + ")")
    }

    private fun readCapped(input: InputStream, encoding: String?, max: Long): String {
        val decoded: InputStream =
            if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(input)
            } else {
                input
            }
        val buffer = ByteArray(READ_CHUNK)
        val out = ByteArrayOutputStream(READ_CHUNK * 8)
        var total = 0L
        while (true) {
            val n = decoded.read(buffer)
            if (n <= 0) break
            total += n
            if (total > max) {
                throw RuntimeException(
                    "Response exceeds " + (max / (1024 * 1024)) + " MB cap"
                )
            }
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    @Synchronized
    private fun readManifest(): JSONArray {
        if (!manifestFile.exists()) return JSONArray()
        return try {
            JSONArray(manifestFile.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Manifest parse failed: " + t.message)
            JSONArray()
        }
    }

    @Synchronized
    private fun writeManifest(arr: JSONArray) {
        manifestFile.writeText(arr.toString(), Charsets.UTF_8)
    }

    @Synchronized
    private fun upsertManifest(entry: Entry) {
        val arr = readManifest()
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url", "") == entry.url) {
                out.put(entryToJson(entry))
                replaced = true
            } else {
                out.put(o)
            }
        }
        if (!replaced) out.put(entryToJson(entry))
        writeManifest(out)
    }

    private fun entryFromJson(o: JSONObject): Entry? {
        val url = o.optString("url", "")
        val file = o.optString("fileName", "")
        if (url.isBlank() || file.isBlank()) return null
        val lastFetched = o.optLong("lastFetched", 0L)
        val lastAttempt = o.optLong("lastAttemptAt", lastFetched)
        return Entry(
            url = url,
            fileName = file,
            byteSize = o.optLong("byteSize", 0L),
            lastFetched = lastFetched,
            lastAttemptAt = lastAttempt,
            ok = o.optBoolean("ok", false),
            error = o.optString("error", "").takeIf { it.isNotBlank() }
        )
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("url", e.url)
        put("fileName", e.fileName)
        put("byteSize", e.byteSize)
        put("lastFetched", e.lastFetched)
        put("lastAttemptAt", e.lastAttemptAt)
        put("ok", e.ok)
        put("error", e.error ?: "")
    }

    private fun shortHash(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        var i = 0
        while (i < 8 && i < bytes.size) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(Integer.toHexString(v).padStart(2, '0'))
            i++
        }
        return sb.toString()
    }
}
