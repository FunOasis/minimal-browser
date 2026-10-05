package com.minimalbrowser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads remote blocklist files (hosts-file format or Adblock-Plus
 * style) and parses out hostnames. Runs on Dispatchers.IO.
 *
 * A "subscription" is a plain URL. We download it, read the text, and
 * extract hosts using the same tolerant parser AdBlocker uses for its
 * built-in assets.
 */
object BlocklistSubscriptions {

    private const val TAG = "BlocklistSubs"
    private const val TIMEOUT_MS = 15_000
    private const val USER_AGENT = "MinimalBrowser/1.0"
    private val WHITESPACE = Regex("\\s+")

    data class FetchResult(
        val hosts: Set<String>,
        val sourcesOk: Int,
        val sourcesFailed: Int
    )

    suspend fun fetch(urls: List<String>): FetchResult = withContext(Dispatchers.IO) {
        val allHosts = HashSet<String>(200_000)
        var ok = 0
        var failed = 0

        for (raw in urls) {
            val url = raw.trim()
            if (url.isEmpty() || url.startsWith("#") || url.startsWith("!")) continue
            try {
                val text = download(url)
                val hosts = parseHosts(text)
                allHosts.addAll(hosts)
                ok++
                Log.i(TAG, "Fetched " + url + " -> " + hosts.size + " hosts")
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Fetch failed for " + url + ": " + e.message)
            }
        }

        FetchResult(allHosts, ok, failed)
    }

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

    /**
     * Accepts:
     *   - hosts-file format: "0.0.0.0 ads.example.com"
     *   - bare hostname:     "ads.example.com"
     *   - Adblock-Plus:      "||ads.example.com^"
     * Comments (# and !) and blanks skipped. Requires a dot in the host
     * to avoid picking up junk tokens.
     */
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
}
