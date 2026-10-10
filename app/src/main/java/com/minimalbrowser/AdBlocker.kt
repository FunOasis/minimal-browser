package com.minimalbrowser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Ad blocker. Rule sources, in priority order:
 *
 *   1. Host entries from the blocklist warehouse (subscription ZIPs
 *      downloaded on demand; empty until the user adds a URL).
 *   2. Host entries from Prefs.customBlocklist.
 *   3. URL substring patterns from Prefs.customFilters.
 *   4. Exception patterns ("@@substring") from Prefs.customFilters.
 *
 * There are NO hardcoded lists. On a fresh install the blocker is
 * inert until the user supplies URLs or custom rules. This is
 * deliberate: the APK ships with no ad-blocking data.
 */
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

    @Volatile private var hostSet: HostSet = HostSet.EMPTY
    @Volatile private var urlPatterns: Set<String> = emptySet()
    @Volatile private var exceptionPatterns: Set<String> = emptySet()
    @Volatile private var hostCount: Int = 0

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
                reloadAllRules()
                blocklistStore?.refreshAll(force = false)
                reloadAllRules()
            }
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

        applyRules(mergedHosts, customPatterns, customExceptions)
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
            if (t.startsWith("@@")) return@forEach
            out.add(t)
        }
        return out
    }

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

        val p = prefs
        if (p != null && host != null && p.isHostDisabled(host)) return false

        decisionCache.get(url)?.let { return it }
        val result = check(lower, host)
        decisionCache.put(url, result)
        return result
    }

    private fun check(lower: String, host: String?): Boolean {
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
