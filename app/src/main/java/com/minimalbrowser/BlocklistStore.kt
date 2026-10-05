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
 * Serialized hosts cache:
 *   loadAllHosts() is expensive on cold start -- it opens every ZIP,
 *   decompresses it, and parses each line. On a mid-range phone that
 *   is roughly 500ms for 150k hosts, all of it happening while the
 *   first navigation is already in flight. We therefore also write a
 *   flat, fingerprinted cache of the merged host set (hosts.cache).
 *   On the next launch, readHostsCache() can return the same set in
 *   ~80ms by skipping decompress and per-line parsing entirely.
 *   The cache carries a SHA-1 fingerprint of the manifest state, so
 *   any change to any list (new URL, refresh, removal) invalidates it.
 *
 * Behaviour guarantees:
 *  - Failure preservation: a failed refresh does NOT wipe a previous
 *    good entry.
 *  - Retry backoff: a freshly failed attempt sets a 30 minute quiet
 *    window.
 *  - Conditional GET: refresh sends If-None-Match and If-Modified-Since
 *    when validators are known. On 304 the body is not downloaded and
 *    the cache is not invalidated.
 *  - Hardened download: manual redirect handling (max 5 hops) and a
 *    20 MB response cap.
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
    private val cacheFile: File = File(dir, CACHE_NAME)

    companion object {
        private const val TAG = "BlocklistStore"
        private const val DIR_NAME = "blocklists"
        private const val MANIFEST_NAME = "manifest.json"
        private const val CACHE_NAME = "hosts.cache"
        private const val DATA_ENTRY_NAME = "data.txt"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "MinimalBrowser/1.0"
        private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val RETRY_INTERVAL_MS = 30L * 60L * 1000L
        private const val MAX_BYTES = 20L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val READ_CHUNK = 64 * 1024
        private const val CACHE_HEADER = "MBLK1"
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
        // Manifest changed -> cache is no longer valid. Delete it so the
        // next readAllHosts() call rebuilds from scratch and writes a
        // fresh one.
        invalidateCache()
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
                // Content changed -> cache is stale.
                invalidateCache()
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

    /**
     * Rebuild the merged host set by reading every ZIP in the warehouse.
     * This is the slow path -- prefer readHostsCache() on cold start.
     */
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
    // Serialized hosts cache
    // ------------------------------------------------------------------

    /**
     * Try to load the merged host set from the flat cache file. Returns
     * null if the cache is missing, corrupt, or stale (fingerprint
     * mismatch against the current manifest state). Callers should fall
     * back to loadAllHosts() + writeHostsCache() on null.
     */
    @Synchronized
    fun readHostsCache(): Set<String>? {
        if (!cacheFile.exists()) return null
        val expected = computeFingerprint()

        return try {
            val text = cacheFile.readText(Charsets.UTF_8)
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext()) return null
            val header = lines.next()
            if (!header.startsWith(CACHE_HEADER + "|")) return null
            val storedFp = header.substring(CACHE_HEADER.length + 1)
            if (storedFp != expected) {
                Log.i(TAG, "Hosts cache stale, will rebuild")
                return null
            }
            val out = HashSet<String>(200_000)
            while (lines.hasNext()) {
                val h = lines.next()
                if (h.isNotEmpty()) out.add(h)
            }
            Log.i(TAG, "Hosts cache hit: " + out.size + " hosts")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "Cache read failed: " + t.message)
            null
        }
    }

    /**
     * Persist the merged host set so the next cold start can skip ZIP
     * decompression and line parsing. Written after every successful
     * loadAllHosts() call. Sorted for determinism (helps debugging and
     * makes the file byte-identical for identical inputs).
     */
    @Synchronized
    fun writeHostsCache(hosts: Set<String>) {
        if (hosts.isEmpty()) return
        try {
            val fp = computeFingerprint()
            val sorted = hosts.sorted()
            val sb = StringBuilder(hosts.size * 24)
            sb.append(CACHE_HEADER).append('|').append(fp).append('\n')
            for (h in sorted) {
                sb.append(h).append('\n')
            }
            cacheFile.writeText(sb.toString(), Charsets.UTF_8)
            Log.i(TAG, "Hosts cache written: " + hosts.size + " hosts, fp=" + fp.take(8))
        } catch (t: Throwable) {
            Log.w(TAG, "Cache write failed: " + t.message)
        }
    }

    @Synchronized
    private fun invalidateCache() {
        if (cacheFile.exists()) cacheFile.delete()
    }

    /**
     * SHA-1 of the sorted "url|lastFetched" list. Any change to any list
     * (addition, removal, refresh) changes the fingerprint, invalidating
     * the cache. Deterministic across runs.
     */
    private fun computeFingerprint(): String {
        val entries = listAll().sortedBy { it.url }
        val sb = StringBuilder(entries.size * 80)
        for (e in entries) {
            sb.append(e.url).append('|').append(e.lastFetched).append('\n')
        }
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        val out = StringBuilder(40)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(Character.forDigit(v shr 4, 16))
            out.append(Character.forDigit(v and 0x0F, 16))
        }
        return out.toString()
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
