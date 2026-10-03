package com.minimalbrowser

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class AdBlocker private constructor(
    private val context: Context?,
    preloadedHosts: Set<String> = emptySet(),
    preloadedPatterns: Set<String> = emptySet(),
    private val autoLoadFromAssets: Boolean = true
) {

    companion object {
        private const val TAG        = "AdBlocker"
        private const val CACHE_SIZE = 4_000

        @Volatile private var instance: AdBlocker? = null

        fun get(context: Context): AdBlocker =
            instance ?: synchronized(this) {
                instance ?: AdBlocker(context.applicationContext).also { instance = it }
            }

        /** JVM-friendly factory for unit tests (no Context, no asset I/O). */
        @JvmStatic
        internal fun forTesting(
            hosts: Set<String>,
            patterns: Set<String>
        ): AdBlocker = AdBlocker(
            context = null,
            preloadedHosts = hosts.map { it.lowercase() }.toSet(),
            preloadedPatterns = patterns.map { it.lowercase() }.toSet(),
            autoLoadFromAssets = false
        )
    }

    private val blockedHosts  = ConcurrentHashMap.newKeySet<String>(50_000)
    private val urlPatterns   = ConcurrentHashMap.newKeySet<String>()
    private val decisionCache = LruCache<String, Boolean>(CACHE_SIZE)

    init {
        blockedHosts.addAll(preloadedHosts)
        urlPatterns.addAll(preloadedPatterns)

        if (autoLoadFromAssets && context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                loadHosts(context)
                loadPatterns(context)
                Log.i(TAG, "Loaded: ${stats()}")
            }
        }
    }

    private fun loadHosts(ctx: Context) {
        readAssetLines(ctx, "blocklist.txt") { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith('#') && !t.startsWith('!')) {
                val parts = t.split("\\s+".toRegex())
                val host  = if (parts.size >= 2) parts[1] else parts[0]
                if (host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                    blockedHosts.add(host.lowercase())
                }
            }
        }
    }

    private fun loadPatterns(ctx: Context) {
        readAssetLines(ctx, "filters.txt") { line ->
            val t = line.trim().lowercase()
            if (t.isNotEmpty() && !t.startsWith('#') && !t.startsWith('!')) {
                urlPatterns.add(t)
            }
        }
    }

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false

        synchronized(decisionCache) {
            decisionCache.get(url)?.let { return it }
        }

        val result = check(url.lowercase())

        synchronized(decisionCache) {
            decisionCache.put(url, result)
        }
        return result
    }

    private fun check(lower: String): Boolean {
        val host = extractHost(lower)
        if (host != null && isHostBlocked(host)) return true

        for (p in urlPatterns) {
            if (lower.contains(p)) return true
        }
        return false
    }

    private fun isHostBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        if (blockedHosts.contains(host)) return true

        var dot = host.indexOf('.')
        while (dot != -1) {
            if (blockedHosts.contains(host.substring(dot + 1))) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    private fun extractHost(url: String): String? {
        return try {
            val uri = URI(url)
            uri.host?.lowercase() ?: fallbackHost(url)
        } catch (_: Exception) {
            fallbackHost(url)
        }
    }

    private fun fallbackHost(url: String): String? {
        val afterScheme = if (url.contains("://")) {
            url.substringAfter("://")
        } else {
            val first = url.substringBefore('/')
            if (!first.contains('.') || first.contains(' ')) return null
            first
        }
        val host = afterScheme.split("/", "?", "#").firstOrNull() ?: return null
        return host.substringBefore(':').takeIf { it.isNotBlank() }?.lowercase()
    }

    private fun readAssetLines(ctx: Context, file: String, action: (String) -> Unit) {
        try {
            ctx.assets.open(file).use { stream ->
                BufferedReader(InputStreamReader(stream), 16_384).use { reader ->
                    reader.forEachLine(action)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $file: ${e.message}")
        }
    }

    data class Stats(val hosts: Int, val patterns: Int)
    fun stats() = Stats(blockedHosts.size, urlPatterns.size)
}
