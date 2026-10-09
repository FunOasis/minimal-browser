package com.minimalbrowser

/**
 * Cosmetic filter rule store.
 *
 * Parsed from EasyList-format sources. Three kinds of rules:
 *
 *   generic:         ##.ad-banner           applied to every page
 *   domain:          example.com##.ad       applied only when the page
 *                                           host matches example.com or
 *                                           a subdomain
 *   negative:        ~example.com##.ad      applied everywhere EXCEPT
 *                                           example.com and subdomains
 *   exception:       example.com#@#.ad      removes .ad from the set for
 *                                           example.com and subdomains
 *
 * Mixed positive/negative domains ("foo.com,~bar.com##sel") apply to
 * foo.com except bar.com, implemented by adding sel to foo.com's
 * include bucket and to bar.com's exception bucket.
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
     * set, then subtracts any matching exception set.
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
         *   ##selector                       -- global include
         *   domain##selector                 -- domain include
         *   domain1,domain2##selector        -- multi-domain include
         *   ~domain##selector                -- global include except on domain
         *   domain1,~domain2##selector       -- domain1 include except domain2
         *   domain#@#selector                -- domain exception
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

                val tokens = parseDomainTokens(domainsPart)

                if (sep.isException) {
                    // Exception rules: only positive domains make sense.
                    // A negative on an exception would mean "allow except
                    // where we disallowed", which EasyList never uses.
                    if (tokens.positives.isEmpty()) return@forEach
                    for (h in tokens.positives) {
                        val set = exByDomain.getOrPut(h) { LinkedHashSet() }
                        if (set.size < perDomainCap) set.add(selector)
                    }
                    return@forEach
                }

                if (tokens.positives.isEmpty() && tokens.negatives.isEmpty()) {
                    // No domain list: plain global rule.
                    if (generic.size < genericCap) generic.add(selector)
                    return@forEach
                }

                if (tokens.positives.isEmpty()) {
                    // Pure negation: apply globally, add exceptions for
                    // each listed domain.
                    if (generic.size < genericCap) generic.add(selector)
                } else {
                    for (h in tokens.positives) {
                        val set = byDomain.getOrPut(h) { LinkedHashSet() }
                        if (set.size < perDomainCap) set.add(selector)
                    }
                }

                for (h in tokens.negatives) {
                    val set = exByDomain.getOrPut(h) { LinkedHashSet() }
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

        private class DomainTokens(
            val positives: List<String>,
            val negatives: List<String>
        )

        /**
         * Locate the rule separator on a line. Returns null if the line
         * is not a cosmetic rule, or uses an unsupported syntax.
         */
        private fun findSeparator(line: String): Sep? {
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

        private val FORBIDDEN = arrayOf(
            "{", "}", ";", "/*", "*/",
            "@",
            "`", "<", ">",
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
         * Split the domain list into positive and negative tokens.
         * A leading "~" marks a negative. Tokens without a dot are
         * ignored (matches the previous stricter behaviour).
         */
        private fun parseDomainTokens(s: String): DomainTokens {
            if (s.isBlank()) return DomainTokens(emptyList(), emptyList())
            val pos = ArrayList<String>(2)
            val neg = ArrayList<String>(2)
            for (part in s.split(',')) {
                var h = part.trim().lowercase().removePrefix("www.")
                if (h.isEmpty()) continue
                val isNeg = h.startsWith('~')
                if (isNeg) h = h.substring(1).trim()
                if (h.isEmpty()) continue
                if (!h.contains('.')) continue
                if (isNeg) neg.add(h) else pos.add(h)
            }
            return DomainTokens(pos, neg)
        }

        private fun hostOf(url: String): String? = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }

        /**
         * Dot-boundary suffixes of a host, stopping before the bare
         * top-level label.
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
