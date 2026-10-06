package com.minimalbrowser

/**
 * Cosmetic filter rule store.
 *
 * Parsed from EasyList-format sources. Three kinds of rules:
 *
 *   generic:   ##.ad-banner         applied to every page
 *   domain:    example.com##.ad     applied only when the page host
 *                                   matches example.com or a subdomain
 *   exception: example.com#@#.ad    removes .ad from the set for
 *                                   example.com and its subdomains
 *
 * Procedural rules (#?#, #$#, #%#, :has(), :has-text(), :xpath(),
 * :-abp-*) are deliberately dropped. They need runtime DOM walks that
 * a MutationObserver-free cosmetic layer cannot provide. The whole
 * point of this design is to ship one CSS block per page and let the
 * browser engine do the rest.
 *
 * Selector safety: selectors are rejected if they contain characters
 * that could escape a CSS declaration block, reference external
 * resources, or unbalance brackets. A single malformed selector in a
 * comma-separated list invalidates the whole rule in a browser, so we
 * filter aggressively at parse time.
 *
 * The class is immutable. Rebuilding from a new subscription just
 * replaces the whole object behind a volatile reference in
 * CosmeticFilter, so readers never see a half-mutated rule set.
 */
class CosmeticRules private constructor(
    private val generic: List<String>,
    private val byDomain: Map<String, List<String>>,
    private val exceptionsByDomain: Map<String, Set<String>>,
    val genericCount: Int,
    val domainCount: Int,
    val domainKeys: Int
) {

    /**
     * Return the concatenated, deduplicated selector list for a page
     * URL. Suffix-walks the host and unions every matching domain rule
     * set, then subtracts any matching exception set. Cheap: no regex,
     * no parsing, just hashing and set operations.
     */
    fun selectorsFor(url: String): List<String> {
        val host = hostOf(url) ?: return generic
        if (byDomain.isEmpty() && exceptionsByDomain.isEmpty()) return generic

        val out = LinkedHashSet<String>(generic.size + 128)
        out.addAll(generic)

        val suffixes = suffixesOf(host)
        for (s in suffixes) {
            byDomain[s]?.let { out.addAll(it) }
        }
        for (s in suffixes) {
            exceptionsByDomain[s]?.let { out.removeAll(it) }
        }
        return ArrayList(out)
    }

    companion object {
        val EMPTY = CosmeticRules(emptyList(), emptyMap(), emptyMap(), 0, 0, 0)

        /**
         * Parse EasyList cosmetic lines into an immutable rule set.
         * Line format reference:
         *
         *   ##selector                 -- global include
         *   domain##selector           -- domain include
         *   domain1,domain2##selector  -- multi-domain include
         *   domain#@#selector          -- domain exception
         */
        fun parse(
            text: String,
            genericCap: Int = 30_000,
            perDomainCap: Int = 800
        ): CosmeticRules {
            val generic = LinkedHashSet<String>(4096)
            val byDomain = HashMap<String, LinkedHashSet<String>>(1024)
            val exByDomain = HashMap<String, LinkedHashSet<String>>(128)

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith('!')) return@forEach   // ABP comment
                if (line.startsWith('[')) return@forEach   // ABP header

                val sep = findSeparator(line) ?: return@forEach

                val domainsPart = line.substring(0, sep.index)
                val selector = line.substring(sep.index + sep.len).trim()
                if (selector.isEmpty()) return@forEach
                if (!isSafeSelector(selector)) return@forEach

                if (domainsPart.isEmpty()) {
                    if (sep.isException) return@forEach
                    if (generic.size < genericCap) generic.add(selector)
                    return@forEach
                }

                val hosts = parseDomains(domainsPart) ?: return@forEach
                if (hosts.isEmpty()) return@forEach

                val bucket = if (sep.isException) exByDomain else byDomain
                for (h in hosts) {
                    val set = bucket.getOrPut(h) { LinkedHashSet() }
                    if (set.size < perDomainCap) set.add(selector)
                }
            }

            var domainCount = 0
            for (v in byDomain.values) domainCount += v.size

            val frozen = HashMap<String, List<String>>(byDomain.size)
            for ((k, v) in byDomain) frozen[k] = ArrayList(v)

            val frozenEx = HashMap<String, Set<String>>(exByDomain.size)
            for ((k, v) in exByDomain) frozenEx[k] = HashSet(v)

            return CosmeticRules(
                generic = ArrayList(generic),
                byDomain = frozen,
                exceptionsByDomain = frozenEx,
                genericCount = generic.size,
                domainCount = domainCount,
                domainKeys = byDomain.size
            )
        }

        // -----------------------------------------------------------------
        // Internals
        // -----------------------------------------------------------------

        private class Sep(val index: Int, val len: Int, val isException: Boolean)

        /**
         * Locate the rule separator on a line. Returns null if the line
         * is not a cosmetic rule, or uses an unsupported syntax.
         *
         * Unsupported forms are rejected early -- cheaper than matching
         * every supported variant, and it keeps procedural rules from
         * being silently mis-parsed as includes.
         */
        private fun findSeparator(line: String): Sep? {
            // Reject the whole line if it uses an extended/snippet/style
            // marker. These are AdGuard / ABP extensions we do not
            // support and must not half-parse.
            if (line.contains("#?#"))  return null
            if (line.contains("#$#"))  return null
            if (line.contains("#%#"))  return null
            if (line.contains("#@?#")) return null
            if (line.contains("#@$#")) return null
            if (line.contains("#@%#")) return null

            var bestIndex = -1
            var bestLen = 0
            var bestIsExc = false
            var found = false

            val idxHash = line.indexOf("##")
            if (idxHash >= 0) {
                bestIndex = idxHash
                bestLen = 2
                bestIsExc = false
                found = true
            }

            val idxExc = line.indexOf("#@#")
            if (idxExc >= 0) {
                if (!found || idxExc < bestIndex) {
                    bestIndex = idxExc
                    bestLen = 3
                    bestIsExc = true
                    found = true
                }
            }

            return if (found) Sep(bestIndex, bestLen, bestIsExc) else null
        }

        /**
         * Characters that cannot appear in a selector we generate CSS
         * for. This is a defensive filter: a single bad entry would
         * invalidate the entire comma-separated rule in the browser.
         */
        private val FORBIDDEN = arrayOf(
            // Could close the CSS declaration block we wrap the list in.
            "{", "}", ";", "/*", "*/",
            // At-rules have no business inside a selector list.
            "@",
            // Could break out of the JS bridge string in a hostile case.
            "`", "<", ">",
            // Procedural / non-standard extensions.
            ":has(", ":has-text(", ":matches-", ":xpath(",
            ":-abp-", ":contains(", ":watch-attr(",
            ":remove(", ":style(", ":if(", ":if-not(",
            ":-abp-contains("
        )

        private fun isSafeSelector(sel: String): Boolean {
            if (sel.length > 400) return false
            for (bad in FORBIDDEN) {
                if (sel.contains(bad)) return false
            }
            // Balanced brackets. A mismatch invalidates the CSS rule.
            var paren = 0
            var sq = 0
            var i = 0
            while (i < sel.length) {
                when (sel[i]) {
                    '(' -> paren++
                    ')' -> paren--
                    '[' -> sq++
                    ']' -> sq--
                }
                if (paren < 0 || sq < 0) return false
                i++
            }
            return paren == 0 && sq == 0
        }

        /**
         * Parse the domain list preceding the separator. Returns null
         * if any entry uses negation (~) -- domain-level negation is
         * not supported. Returns an empty list if the list is
         * malformed.
         */
        private fun parseDomains(s: String): List<String>? {
            val out = ArrayList<String>(2)
            for (part in s.split(',')) {
                val h = part.trim().lowercase()
                if (h.isEmpty()) continue
                if (h.startsWith('~')) return null
                if (!h.contains('.')) continue
                out.add(h.removePrefix("www."))
            }
            return out
        }

        private fun hostOf(url: String): String? = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }

        /**
         * Dot-boundary suffixes of a host, stopping before the bare
         * top-level label.
         *   a.b.example.com -> [a.b.example.com, b.example.com, example.com]
         */
        private fun suffixesOf(host: String): List<String> {
            val out = ArrayList<String>(6)
            var cur = host
            while (true) {
                out.add(cur)
                val dot = cur.indexOf('.')
                if (dot < 0) break
                val rest = cur.substring(dot + 1)
                if (rest.indexOf('.') < 0) break
                cur = rest
            }
            return out
        }
    }
}
