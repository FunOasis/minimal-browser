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
 * The warehouse.
 *
 * Every subscription URL is downloaded once, parsed into a set of
 * hostnames, and stored as a ZIP-compressed text file inside the app's
 * private storage at filesDir/blocklists/. A sidecar manifest.json
 * records one entry per list.
 *
 * Behaviour guarantees:
 *  - Failure preservation: a failed refresh does NOT wipe a previous
 *    good entry. The ZIP on disk is left untouched so blocking keeps
 *    working; only lastAttemptAt and error are updated.
 *  - Retry backoff: a freshly failed attempt sets a 30 minute quiet
 *    window so we do not hammer a down server on every launch.
 *  - Conditional GET: refresh sends If-None-Match and If-Modified-Since
 *    when validators are known. On 304 the body is not downloaded and
 *    the trie is not rebuilt -- only the timestamps are bumped.
 *  - Hardened download: manual redirect handling (max 5 hops) and a
 *    20 MB response cap so a broken URL cannot exhaust memory.
 */
class BlocklistStore private constructor(private val appContext: Context) {

    data class Entry(
        val url: String,
        val fileName: String,
        val hostCount: Int,
        val byteSize: Long,
        val lastFetched: Long,
        val lastAttemptAt: Long,
        val ok: Boolean,
        val error: String?,
        val etag: String?,
        val lastModified: String?
    )

    data class RefreshSummary(
        val refreshed: Int,
        val failed: Int,
        val skipped: Int,
        val totalHosts: Int
    )

    private data class DownloadResult(
        val text: String?,
        val notModified: Boolean,
        val etag: String?,
        val lastModified: String?
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
        private const val RETRY_INTERVAL_MS = 30L * 60L * 1000L
        private const val MAX_BYTES = 20L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val READ_CHUNK = 64 * 1024
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
        addAndFetchInternal(url).first
    }

    private suspend fun addAndFetchInternal(url: String): Pair<Entry, Boolean> =
        withContext(Dispatchers.IO) {
            val clean = url.trim()
            val existing = listAll().firstOrNull { it.url == clean }
            val fileName = existing?.fileName ?: ("list_" + shortHash(clean) + ".zip")
            val now = System.currentTimeMillis()

            try {
                val prevEtag = if (existing?.ok == true) existing.etag else null
                val prevLM = if (existing?.ok == true) existing.lastModified else null
                val result = download(clean, prevEtag, prevLM)

                if (result.notModified) {
                    // Server confirms nothing changed. Keep the ZIP on disk
                    // untouched, skip the parse, and just bump timestamps.
                    val base = existing
                    if (base == null) {
                        throw RuntimeException("304 with no cached entry")
                    }
                    val entry = base.copy(
                        lastFetched = now,
                        lastAttemptAt = now,
                        ok = true,
                        error = null,
                        etag = result.etag ?: base.etag,
                        lastModified = result.lastModified ?: base.lastModified
                    )
                    upsertManifest(entry)
                    Log.i(TAG, "Not modified " + clean +
                        " (kept " + entry.hostCount + " hosts)")
                    return@withContext entry to true
                }

                val text = result.text ?: ""
                val hosts = parseHosts(text)
                val target = File(dir, fileName)
                writeZip(target, text)
                val bytes = target.length()

                val entry = Entry(
                    url = clean,
                    fileName = fileName,
                    hostCount = hosts.size,
                    byteSize = bytes,
                    lastFetched = now,
                    lastAttemptAt = now,
                    ok = true,
                    error = null,
                    etag = result.etag,
                    lastModified = result.lastModified
                )
                upsertManifest(entry)
                Log.i(TAG, "Fetched " + clean + " -> " + hosts.size +
                    " hosts, " + bytes + " bytes")
                entry to false
            } catch (t: Throwable) {
                Log.w(TAG, "Fetch failed for " + clean + ": " + t.message)
                val previous = existing ?: Entry(
                    url = clean,
                    fileName = fileName,
                    hostCount = 0,
                    byteSize = 0L,
                    lastFetched = 0L,
                    lastAttemptAt = 0L,
                    ok = false,
                    error = null,
                    etag = null,
                    lastModified = null
                )
                val stillGood = previous.lastFetched > 0L &&
                    File(dir, previous.fileName).exists()
                val updated = previous.copy(
                    lastAttemptAt = now,
                    ok = stillGood,
                    error = t.message ?: "unknown"
                )
                upsertManifest(updated)
                updated to false
            }
        }

    suspend fun refreshAll(force: Boolean): RefreshSummary = withContext(Dispatchers.IO) {
        val entries = listAll()
        var refreshed = 0
        var failed = 0
        var skipped = 0
        var notModified = 0
        val now = System.currentTimeMillis()

        for (e in entries) {
            val fresh = e.ok && (now - e.lastFetched < REFRESH_INTERVAL_MS)
            val recentlyTried = (now - e.lastAttemptAt) < RETRY_INTERVAL_MS
            if (!force && (fresh || recentlyTried)) {
                skipped++
                continue
            }
            val pair = addAndFetchInternal(e.url)
            val result = pair.first
            val wasNotModified = pair.second
            if (result.ok) {
                refreshed++
                if (wasNotModified) notModified++
            } else {
                failed++
            }
        }

        var totalHosts = 0
        for (e in listAll()) if (e.ok) totalHosts += e.hostCount

        Log.i(TAG, "Refresh done: " + refreshed + " ok (" + notModified +
            " not modified), " + failed + " failed, " + skipped + " skipped, " +
            totalHosts + " hosts")
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
    // HTTP with conditional GET, manual redirects and a size cap
    // ------------------------------------------------------------------

    private fun download(
        url: String,
        prevEtag: String?,
        prevLastModified: String?
    ): DownloadResult {
        var current = url
        var hops = 0
        var carriedEtag = prevEtag
        var carriedLastModified = prevLastModified

        while (hops <= MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = false

                if (!carriedEtag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", carriedEtag)
                }
                if (!carriedLastModified.isNullOrBlank()) {
                    setRequestProperty("If-Modified-Since", carriedLastModified)
                }
            }
            try {
                val code = conn.responseCode
                when {
                    code == 304 -> {
                        return DownloadResult(
                            text = null,
                            notModified = true,
                            etag = conn.getHeaderField("ETag") ?: carriedEtag,
                            lastModified = conn.getHeaderField("Last-Modified")
                                ?: carriedLastModified
                        )
                    }
                    code in 200..299 -> {
                        val body = readCapped(conn.inputStream, MAX_BYTES)
                        return DownloadResult(
                            text = body,
                            notModified = false,
                            etag = conn.getHeaderField("ETag"),
                            lastModified = conn.getHeaderField("Last-Modified")
                        )
                    }
                    code == 301 || code == 302 || code == 303 ||
                    code == 307 || code == 308 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw RuntimeException("HTTP " + code + " without Location")
                        current = URL(URL(current), location).toString()
                        hops++
                        // Drop conditional headers on redirect: the target is
                        // a different resource, and some servers will return
                        // a spurious 304 if validators cross origins.
                        carriedEtag = null
                        carriedLastModified = null
                    }
                    else -> throw RuntimeException("HTTP " + code)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw RuntimeException("Too many redirects (>" + MAX_REDIRECTS + ")")
    }

    private fun readCapped(input: InputStream, max: Long): String {
        val buffer = ByteArray(READ_CHUNK)
        val out = ByteArrayOutputStream(READ_CHUNK * 4)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
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
        val lastFetched = o.optLong("lastFetched", 0L)
        val lastAttempt = o.optLong("lastAttemptAt", lastFetched)
        return Entry(
            url = url,
            fileName = file,
            hostCount = o.optInt("hostCount", 0),
            byteSize = o.optLong("byteSize", 0L),
            lastFetched = lastFetched,
            lastAttemptAt = lastAttempt,
            ok = o.optBoolean("ok", false),
            error = o.optString("error", "").takeIf { it.isNotBlank() },
            etag = o.optString("etag", "").takeIf { it.isNotBlank() },
            lastModified = o.optString("lastModified", "").takeIf { it.isNotBlank() }
        )
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("url", e.url)
        put("fileName", e.fileName)
        put("hostCount", e.hostCount)
        put("byteSize", e.byteSize)
        put("lastFetched", e.lastFetched)
        put("lastAttemptAt", e.lastAttemptAt)
        put("ok", e.ok)
        put("error", e.error ?: "")
        put("etag", e.etag ?: "")
        put("lastModified", e.lastModified ?: "")
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
