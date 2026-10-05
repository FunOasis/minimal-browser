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
        private const val TAG = "AdBlocker"
        private const val CACHE_SIZE = 8_000
        private val WHITESPACE = Regex("\\s+")

        @Volatile private var instance: AdBlocker? = null

        fun get(context: Context): AdBlocker =
            instance ?: synchronized(this) {
                instance ?: AdBlocker(context.applicationContext).also { instance = it }
            }

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
    @Volatile private var basePatterns: Set<String> = emptySet()

    @Volatile private var blocklistStore: BlocklistStore? = null
    @Volatile private var prefs: Prefs? = null

    private val decisionCache = SimpleLruCache<String, Boolean>(CACHE_SIZE)

    init {
        if (preloadedHosts.isNotEmpty() || preloadedPatterns.isNotEmpty()) {
            applyRules(preloadedHosts, preloadedPatterns)
        }

        val ctx = context
        if (autoLoadFromAssets && ctx != null) {
            blocklistStore = BlocklistStore.get(ctx)
            prefs = Prefs.get(ctx)

            CoroutineScope(Dispatchers.IO).launch {
                loadBasePatterns(ctx)
                seedDefaultsIfNeeded()
                reloadAllRules()
                // Second pass: refresh stale lists over the network, then
                // rebuild the trie if anything actually changed on disk.
                blocklistStore?.refreshAll(force = false)
                reloadAllRules()
            }
        }
    }

    private fun loadBasePatterns(ctx: Context) {
        val filterText = readAssetText(ctx, "filters.txt")
        basePatterns = parsePatterns(filterText)
    }

    /**
     * First-ever launch: the warehouse is empty, so seed the default
     * subscription URLs. Every subsequent launch short-circuits here.
     */
    private suspend fun seedDefaultsIfNeeded() {
        val store = blocklistStore ?: return
        if (store.listAll().isNotEmpty()) return
        val defaults = Prefs.DEFAULT_SUBSCRIPTION_URLS
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .toList()
        Log.i(TAG, "Seeding " + defaults.size + " default subscription lists")
        for (url in defaults) {
            store.addAndFetch(url)
        }
    }

    private suspend fun reloadAllRules() {
        val store = blocklistStore ?: return
        val p = prefs ?: return

        val subHosts = store.loadAllHosts()
        val customHosts = parseHosts(p.customBlocklist)
        val customPatterns = parsePatterns(p.customFilters)

        val mergedHosts = HashSet<String>(subHosts.size + customHosts.size)
        mergedHosts.addAll(subHosts)
        mergedHosts.addAll(customHosts)

        val mergedPatterns =
            if (customPatterns.isEmpty()) basePatterns else basePatterns + customPatterns

        applyRules(mergedHosts, mergedPatterns)
        Log.i(TAG, "Rules applied: " + stats().toString())
    }

    fun reloadCustomRules() {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            if (blocklistStore == null) blocklistStore = BlocklistStore.get(ctx)
            if (prefs == null) prefs = Prefs.get(ctx)
            reloadAllRules()
        }
    }

    fun refreshSubscriptionsAsync(force: Boolean) {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            if (blocklistStore == null) blocklistStore = BlocklistStore.get(ctx)
            if (prefs == null) prefs = Prefs.get(ctx)
            blocklistStore?.refreshAll(force)
            reloadAllRules()
        }
    }

    @Synchronized
    private fun applyRules(hosts: Set<String>, patterns: Set<String>) {
        val trie = SuffixTrie()
        for (h in hosts) trie.insert(reverseLabels(h))
        hostTrie = trie
        urlPatterns = patterns
        hostCount = hosts.size
        decisionCache.evictAll()
    }

    private fun readAssetText(ctx: Context, file: String): String {
        return try {
            ctx.assets.open(file)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load " + file + ": " + e.message)
            ""
        }
    }

    private fun parseHosts(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(1024)
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('#') || t.startsWith('!')) return@forEach
            val parts = t.split(WHITESPACE)
            val host = if (parts.size >= 2) parts[1] else parts[0]
            if (host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                out.add(host.lowercase())
            }
        }
        return out
    }

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

    data class Stats(val hosts: Int, val patterns: Int) {
        override fun toString(): String = "hosts=" + hosts + " patterns=" + patterns
    }

    fun stats() = Stats(hostCount, urlPatterns.size)

    private class SimpleLruCache<K, V>(private val maxSize: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                return size > maxSize
            }
        }
        @Synchronized fun get(key: K): V? = map[key]
        @Synchronized fun put(key: K, value: V) { map[key] = value }
        @Synchronized fun evictAll() { map.clear() }
    }
}
