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

    // Stage 6: flat sorted LongArray of FNV-1a hashes over reversed
    // hostnames. ~1.2 MB for 150k hosts vs ~35 MB for the trie.
    @Volatile private var hostSet: HostSet = HostSet.EMPTY
    @Volatile private var urlPatterns: Set<String> = emptySet()
    @Volatile private var exceptionPatterns: Set<String> = emptySet()
    @Volatile private var hostCount: Int = 0
    @Volatile private var basePatterns: Set<String> = emptySet()
    @Volatile private var baseExceptions: Set<String> = emptySet()

    @Volatile private var blocklistStore: BlocklistStore? = null
    @Volatile private var prefs: Prefs? = null

    private val decisionCache = SimpleLruCache<String, Boolean>(CACHE_SIZE)

    init {
        if (preloadedHosts.isNotEmpty() || preloadedPatterns.isNotEmpty()) {
            applyRules(preloadedHosts, preloadedPatterns, emptySet())
        }

        val ctx = context
        if (autoLoadFromAssets && ctx != null) {
            blocklistStore = BlocklistStore.get(ctx)
            prefs = Prefs.get(ctx)

            CoroutineScope(Dispatchers.IO).launch {
                loadBasePatterns(ctx)
                seedDefaultsIfNeeded()
                reloadAllRules()
                blocklistStore?.refreshAll(force = false)
                reloadAllRules()
            }
        }
    }

    private fun loadBasePatterns(ctx: Context) {
        val filterText = readAssetText(ctx, "filters.txt")
        basePatterns = parsePatterns(filterText)
        baseExceptions = parseExceptions(filterText)
    }

    private suspend fun seedDefaultsIfNeeded() {
    val store = blocklistStore ?: return
    val p = prefs ?: return

    // Seed once per install. Without this guard, an empty warehouse
    // is indistinguishable from a fresh install, so clearing the
    // subscription list would be silently undone on the next cold
    // start when AdBlocker re-seeds the defaults.
    if (p.blocklistSeeded) return
    p.blocklistSeeded = true

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

        val cached = store.readHostsCache()
        val subHosts = if (cached != null) {
            cached
        } else {
            val fresh = store.loadAllHosts()
            store.writeHostsCache(fresh)
            fresh
        }

        val customHosts = parseHosts(p.customBlocklist)
        val customPatterns = parsePatterns(p.customFilters)
        val customExceptions = parseExceptions(p.customFilters)

        val mergedHosts = HashSet<String>(subHosts.size + customHosts.size)
        mergedHosts.addAll(subHosts)
        mergedHosts.addAll(customHosts)

        val mergedPatterns =
            if (customPatterns.isEmpty()) basePatterns else basePatterns + customPatterns
        val mergedExceptions =
            if (customExceptions.isEmpty()) baseExceptions else baseExceptions + customExceptions

        applyRules(mergedHosts, mergedPatterns, mergedExceptions)
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

    /**
     * Called by MainActivity after the user toggles a per-site
     * whitelist entry, so the decision cache does not serve a stale
     * result for the site the user just changed.
     */
    fun invalidateCache() {
        decisionCache.evictAll()
    }

    @Synchronized
    private fun applyRules(
        hosts: Set<String>,
        patterns: Set<String>,
        exceptions: Set<String>
    ) {
        hostSet = HostSet.from(hosts)
        urlPatterns = patterns
        exceptionPatterns = exceptions
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

    /**
     * Regular URL substring patterns. Lines beginning with @@ are
     * skipped here and handled by parseExceptions().
     */
    private fun parsePatterns(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(256)
        text.lineSequence().forEach { line ->
            val t = line.trim().lowercase()
            if (t.isEmpty() || t.startsWith('#') || t.startsWith('!')) return@forEach
            if (t.startsWith("@@")) return@forEach
            out.add(t)
        }
        return out
    }

    /**
     * Exception patterns: a request whose URL contains one of these
     * substrings is allowed even if it would otherwise match a host or
     * pattern rule.
     *
     * Supported syntax: "@@substring". For convenience, leading "||"
     * or "|" and trailing "^" or "|" (ABP delimiters) are stripped so
     * a line copied verbatim from an ABP list still works as a plain
     * substring match against the lowercased URL.
     */
    private fun parseExceptions(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(32)
        text.lineSequence().forEach { line ->
            var t = line.trim().lowercase()
            if (t.isEmpty() || t.startsWith('#') || t.startsWith('!')) return@forEach
            if (!t.startsWith("@@")) return@forEach
            t = t.substring(2).trim()
            if (t.startsWith("||")) t = t.substring(2)
            if (t.startsWith("|")) t = t.substring(1)
            if (t.endsWith("^")) t = t.substring(0, t.length - 1)
            if (t.endsWith("|")) t = t.substring(0, t.length - 1)
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        val host = extractHost(lower)

        // Per-site whitelist. Checked before the decision cache so that
        // toggling the whitelist takes effect immediately.
        val p = prefs
        if (p != null && host != null && p.isHostDisabled(host)) return false

        decisionCache.get(url)?.let { return it }
        val result = check(lower, host)
        decisionCache.put(url, result)
        return result
    }

    private fun check(lower: String, host: String?): Boolean {
        // Exceptions first: an explicit allow wins over everything else.
        if (exceptionPatterns.isNotEmpty()) {
            for (p in exceptionPatterns) {
                if (lower.contains(p)) return false
            }
        }
        if (host != null && hostSet.matches(host)) return true
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
