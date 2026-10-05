package com.minimalbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The warehouse.
 *
 * Every subscription URL is downloaded once, parsed into a set of
 * hostnames, and stored as a ZIP-compressed text file inside the app's
 * private storage at filesDir/blocklists/. A sidecar manifest.json
 * records one entry per list -- source URL, ZIP filename, host count,
 * byte size, last-fetch time. The adblock engine reads every ZIP back
 * into a single trie at load time; nothing outside this process ever
 * touches these files.
 *
 * Per-list 24h refresh: each entry carries its own lastFetched
 * timestamp and is refreshed independently on the next opportunity.
 */
class BlocklistStore private constructor(private val appContext: Context) {

    data class Entry(
        val url: String,
        val fileName: String,
        val hostCount: Int,
        val byteSize: Long,
        val lastFetched: Long,
        val ok: Boolean,
        val error: String?
    )

    data class RefreshSummary(
        val refreshed: Int,
        val failed: Int,
        val skipped: Int,
        val totalHosts: Int
    )

    private val dir: File = File(appContext.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }
    private val manifestFile: File = File(dir, MANIFEST_NAME)

    companion object {
        private const val TAG = "BlocklistStore"
        private const val DIR_NAME = "blocklists"
        private const val MANIFEST_NAME = "manifest.json"
        private const val DATA_ENTRY_NAME = "data.txt"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "MinimalBrowser/1.0"
        private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private val WHITESPACE = Regex("\\s+")

        @Volatile private var inst: BlocklistStore? = null

        fun get(ctx: Context): BlocklistStore =
            inst ?: synchronized(this) {
                inst ?: BlocklistStore(ctx.applicationContext).also { inst = it }
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
        Log.i(TAG, "Removed list " + url)
    }

    suspend fun addAndFetch(url: String): Entry = withContext(Dispatchers.IO) {
        val clean = url.trim()
        val fileName = "list_" + shortHash(clean) + ".zip"
        try {
            val text = download(clean)
            val hosts = parseHosts(text)
            writeZip(File(dir, fileName), text)
            val bytes = File(dir, fileName).length()
            val entry = Entry(
                url = clean,
                fileName = fileName,
                hostCount = hosts.size,
                byteSize = bytes,
                lastFetched = System.currentTimeMillis(),
                ok = true,
                error = null
            )
            upsertManifest(entry)
            Log.i(TAG, "Fetched " + clean + " -> " + hosts.size + " hosts, " + bytes + " bytes")
            entry
        } catch (t: Throwable) {
            Log.w(TAG, "Fetch failed for " + clean + ": " + t.message)
            val entry = Entry(
                url = clean,
                fileName = fileName,
                hostCount = 0,
                byteSize = 0L,
                lastFetched = System.currentTimeMillis(),
                ok = false,
                error = t.message ?: "unknown"
            )
            upsertManifest(entry)
            entry
        }
    }

    suspend fun refreshAll(force: Boolean): RefreshSummary = withContext(Dispatchers.IO) {
        val entries = listAll()
        var refreshed = 0
        var failed = 0
        var skipped = 0
        val now = System.currentTimeMillis()

        for (e in entries) {
            val stale = now - e.lastFetched >= REFRESH_INTERVAL_MS
            if (!force && !stale) {
                skipped++
                continue
            }
            val result = addAndFetch(e.url)
            if (result.ok) refreshed++ else failed++
        }

        var totalHosts = 0
        for (e in listAll()) totalHosts += e.hostCount

        Log.i(TAG, "Refresh done: " + refreshed + " ok, " + failed +
            " failed, " + skipped + " skipped, " + totalHosts + " hosts")
        RefreshSummary(refreshed, failed, skipped, totalHosts)
    }

    suspend fun loadAllHosts(): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>(200_000)
        for (e in listAll()) {
            if (!e.ok) continue
            try {
                val text = readZip(File(dir, e.fileName))
                out.addAll(parseHosts(text))
            } catch (t: Throwable) {
                Log.w(TAG, "Read failed for " + e.fileName + ": " + t.message)
            }
        }
        out
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
    // HTTP
    // ------------------------------------------------------------------

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP " + code)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHosts(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(4096)
        text.lineSequence().forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#") || line.startsWith("!")) return@forEach

            if (line.startsWith("||")) {
                line = line.removePrefix("||")
                val cut = line.indexOfFirst { it == '^' || it == '/' || it == '$' }
                if (cut > 0) line = line.substring(0, cut)
                if (line.isNotBlank() && line.contains('.')) out.add(line.lowercase())
                return@forEach
            }

            val parts = line.split(WHITESPACE)
            val host = if (parts.size >= 2) parts[1] else parts[0]
            if (host.isBlank() || host == "0.0.0.0" || host == "127.0.0.1") return@forEach
            if (!host.contains('.')) return@forEach
            out.add(host.lowercase())
        }
        return out
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
        return Entry(
            url = url,
            fileName = file,
            hostCount = o.optInt("hostCount", 0),
            byteSize = o.optLong("byteSize", 0L),
            lastFetched = o.optLong("lastFetched", 0L),
            ok = o.optBoolean("ok", false),
            error = o.optString("error", "").takeIf { it.isNotBlank() }
        )
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("url", e.url)
        put("fileName", e.fileName)
        put("hostCount", e.hostCount)
        put("byteSize", e.byteSize)
        put("lastFetched", e.lastFetched)
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
