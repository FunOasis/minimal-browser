package com.minimalbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI

class AdBlocker private constructor(
    private val context: Context?,
    preloadedHosts: Set<String> = emptySet(),
    preloadedPatterns: Set<String> = emptySet(),
    private val autoLoadFromAssets: Boolean = true
) {

    companion object {
        private const val TAG        = "AdBlocker"

        /**
         * 8k entries @ ~150 bytes/key ≈ 1.2 MB of RAM. Waaaaay more than
         * enough for a typical browsing session, and the eviction path
         * only fires under sustained heavy browsing.
         */
        private const val CACHE_SIZE = 8_000

        /**
         * Compiled once. Kotlin's `String.split(regex: String)` does NOT
         * cache the compiled pattern, so building it inline per blocklist
         * line would recompile ~50,000 times at boot. Hoisted here, we
         * compile it exactly once.
         */
        private val WHITESPACE = Regex("\\s+")

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

    // -------------------------------------------------------------------
    // Host matcher: reverse-label suffix trie.
    // -------------------------------------------------------------------
    private class SuffixTrie {
        private class Node {
            val children = HashMap<String, Node>(4)
            var terminal = false
        }

        private val root = Node()

        fun insert(reversedLabels: List<String>) {
            if (reversedLabels.isEmpty()) return
            var node = root
            for (label in reversedLabels) {
                node = node.children.getOrPut(label) { Node() }
            }
            node.terminal = true
        }

        /** True if any prefix of [reversedLabels] lands on a terminal node. */
        fun matches(reversedLabels: List<String>): Boolean {
            var node = root
            for (label in reversedLabels) {
                node = node.children[label] ?: return false
                if (node.terminal) return true
            }
            return false
        }
    }

    @Volatile private var hostTrie: SuffixTrie = SuffixTrie()
    @Volatile private var urlPatterns: Set<String> = emptySet()
    @Volatile private var hostCount: Int = 0

    // -------------------------------------------------------------------
    // Plain-JVM LRU cache.
    // android.util.LruCache throws "Stub!" under plain JVM unit tests.
    // -------------------------------------------------------------------
    private val decisionCache = SimpleLruCache<String, Boolean>(CACHE_SIZE)

    init {
        if (preloadedHosts.isNotEmpty() || preloadedPatterns.isNotEmpty()) {
            applyRules(preloadedHosts, preloadedPatterns)
        }

        if (autoLoadFromAssets && context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                loadFromAssets(context)
            }
        }
    }

    private fun applyRules(hosts: Set<String>, patterns: Set<String>) {
        val trie = SuffixTrie()
        for (h in hosts) trie.insert(reverseLabels(h))
        hostTrie = trie
        urlPatterns = patterns
        hostCount = hosts.size
        decisionCache.evictAll()
    }

    /**
     * "ads.example.com" -> ["com", "example", "ads"].
     * Manual scan to avoid regex / array churn on the boot path.
     */
    private fun reverseLabels(host: String): List<String> {
        if (host.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        var end = host.length
        var i = host.length - 1
        while (i >= 0) {
            if (host[i] == '.') {
                if (i + 1 < end) out.add(host.substring(i + 1, end))
                end = i
            }
            i--
        }
        if (end > 0) out.add(host.substring(0, end))
        return out
    }

    private fun loadFromAssets(ctx: Context) {
        val hosts    = HashSet<String>(50_000)
        val patterns = HashSet<String>(2_000)

        readAssetLines(ctx, "blocklist.txt") { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith('#') && !t.startsWith('!')) {
                // Hoisted WHITESPACE regex — see companion.
                val parts = t.split(WHITESPACE)
                val host  = if (parts.size >= 2) parts[1] else parts[0]
                if (host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                    hosts.add(host.lowercase())
                }
            }
        }

        readAssetLines(ctx, "filters.txt") { line ->
            val t = line.trim().lowercase()
            if (t.isNotEmpty() && !t.startsWith('#') && !t.startsWith('!')) {
                patterns.add(t)
            }
        }

        applyRules(hosts, patterns)
        Log.i(TAG, "Loaded: ${stats()}")
    }

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false

        decisionCache.get(url)?.let { return it }

        val result = check(url.lowercase())

        decisionCache.put(url, result)
        return result
    }

    private fun check(lower: String): Boolean {
        val host = extractHost(lower)
        if (host != null && hostTrie.matches(reverseLabels(host))) return true

        for (p in urlPatterns) {
            if (lower.contains(p)) return true
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
            ctx.assets.open(file).use { stream -> readLines(stream, action) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $file: ${e.message}")
        }
    }

    private fun readLines(stream: InputStream, action: (String) -> Unit) {
        BufferedReader(InputStreamReader(stream), 16_384).use { reader ->
            reader.forEachLine(action)
        }
    }

    data class Stats(val hosts: Int, val patterns: Int)
    fun stats() = Stats(hostCount, urlPatterns.size)

    private class SimpleLruCache<K, V>(private val maxSize: Int) {

        private val map = object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                return size > maxSize
            }
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun evictAll() {
            map.clear()
        }
    }
}
