package com.minimalbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
         * 8k entries @ ~150 bytes/key ≈ 1.2 MB of RAM. More than enough
         * for a browsing session; the eviction path only fires under
         * sustained heavy browsing.
         */
        private const val CACHE_SIZE = 8_000

        /**
         * Compiled once. Kotlin's String.split(regex: String) does NOT
         * cache the compiled pattern, so building it inline per blocklist
         * line would recompile ~50,000 times at boot.
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

    /**
     * Built-in rules from assets/blocklist.txt and assets/filters.txt.
     * Populated once at boot and never modified afterwards.
     */
    @Volatile private var baseHosts: Set<String> = emptySet()
    @Volatile private var basePatterns: Set<String> = emptySet()

    // -------------------------------------------------------------------
    // Plain-JVM LRU cache. android.util.LruCache throws "Stub!" under
    // plain JVM unit tests, so we ship our own.
    // -------------------------------------------------------------------
    private val decisionCache = SimpleLruCache<String, Boolean>(CACHE_SIZE)

    init {
        if (preloadedHosts.isNotEmpty() || preloadedPatterns.isNotEmpty()) {
            baseHosts = preloadedHosts
            basePatterns = preloadedPatterns
            applyRules(preloadedHosts, preloadedPatterns)
        }

        if (autoLoadFromAssets && context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                loadFromAssets(context)
            }
        }
    }

    // -------------------------------------------------------------------
    // Rule loading + merging
    // -------------------------------------------------------------------

    private fun loadFromAssets(ctx: Context) {
        val hostText   = readAssetText(ctx, "blocklist.txt")
        val filterText = readAssetText(ctx, "filters.txt")

        baseHosts    = parseHosts(hostText)
        basePatterns = parsePatterns(filterText)

        applyMergedRules()
    }

    /**
     * Merges base (built-in) rules with any user-supplied rules from
     * Prefs, then atomically publishes the result. Called at boot and
     * whenever the Custom filters dialog saves.
     *
     * Safe to call from any thread. Because applyRules() clears the
     * decision cache, the new rules take effect on the very next
     * shouldInterceptRequest — no restart, no re-navigation required.
     */
    private fun applyMergedRules() {
        val ctx = context ?: return

        val p = Prefs.get(ctx)
        val customHosts    = parseHosts(p.customBlocklist)
        val customPatterns = parsePatterns(p.customFilters)

        val mergedHosts =
            if (customHosts.isEmpty()) baseHosts else baseHosts + customHosts
        val mergedPatterns =
            if (customPatterns.isEmpty()) basePatterns else basePatterns + customPatterns

        applyRules(mergedHosts, mergedPatterns)
        Log.i(TAG, "Rules applied: ${stats()}")
    }

    /**
     * Public entry point for the UI. Fires off a background reload of
     * merged rules after the user saves custom filters.
     */
    fun reloadCustomRules() {
        if (context == null) return
        CoroutineScope(Dispatchers.IO).launch {
            applyMergedRules()
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

    // -------------------------------------------------------------------
    // Text parsing — shared between assets and user-pasted rules
    // -------------------------------------------------------------------

    private fun readAssetText(ctx: Context, file: String): String {
        return try {
            ctx.assets.open(file)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $file: ${e.message}")
            ""
        }
    }

    /**
     * Parse a blocklist text blob. Accepts:
     *   - one bare host per line ("doubleclick.net")
     *   - hosts-file format ("0.0.0.0 doubleclick.net")
     *   - comments starting with # or !
     * Blank lines and 0.0.0.0 / 127.0.0.1 sentinel rows are ignored.
     */
    private fun parseHosts(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(1024)
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('#') || t.startsWith('!')) return@forEach
            val parts = t.split(WHITESPACE)
            val host  = if (parts.size >= 2) parts[1] else parts[0]
            if (host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                out.add(host.lowercase())
            }
        }
        return out
    }

    /**
     * Parse a URL keyword pattern blob. One keyword per line, lowercased,
     * # / ! comments skipped.
     */
    private fun parsePatterns(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(256)
        text.lineSequence().forEach { line ->
            val t = line.trim().lowercase()
            if (t.isEmpty() || t.startsWith('#') || t.startsWith('!')) return@forEach
            out.add(t)
        }
        return out
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

    // -------------------------------------------------------------------
    // Hot path
    // -------------------------------------------------------------------

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

    data class Stats(val hosts: Int, val patterns: Int)
    fun stats() = Stats(hostCount, urlPatterns.size)

    // -------------------------------------------------------------------
    // Plain-JVM LRU cache
    // -------------------------------------------------------------------
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
