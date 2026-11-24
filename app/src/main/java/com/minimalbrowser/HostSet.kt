package com.minimalbrowser

/**
 * Compact immutable set of hashed reversed hostnames.
 *
 * Replaces the SuffixTrie that AdBlocker used to carry. The trie was
 * semantically correct but memory-hungry: every host contributed a chain
 * of Node objects, each with its own HashMap<String, Node> and its own
 * distinct String key per label. At 150k blocked hosts the merged trie
 * held roughly 300k nodes and 400k key Strings -- on the order of 30-40
 * MB of live old-generation heap.
 *
 * This class stores the same information as a single sorted LongArray:
 * one 64-bit FNV-1a hash per blocked host, computed over the host's
 * reversed-label form ("ads.example.com" becomes hash("com.example.ads")).
 * Membership is a binary search -- O(log n) with n = host count.
 * Footprint for 150k hosts: 1.2 MB.
 *
 * Matching semantics are identical to the trie. A query host H is
 * blocked if any suffix of H at a dot boundary is present in the set.
 * For H = "x.ads.example.com", the candidate suffixes are:
 *
 *   com
 *   com.example
 *   com.example.ads        <- hit, blocked
 *   com.example.ads.x
 *
 * Zero-allocation hot path
 * ------------------------
 * matches() is called from shouldInterceptRequest for every
 * non-mainframe request, i.e. dozens of times per page. An earlier
 * version of this class called a reverseHost() helper that built the
 * reversed string with host.split('.') (allocates a List<String> plus
 * one String per label) and a StringBuilder (allocates the builder,
 * its backing char array, and the final String) before the hash walk
 * ever started -- so despite the "zero-allocation" doc comment, every
 * single sub-resource request paid for a List, N label Strings, a
 * StringBuilder, and a final String.
 *
 * This version never builds the reversed string. It walks the ORIGINAL
 * host from right to left, one label at a time, using plain index
 * arithmetic to find each label's [start, end) bounds. Within a label
 * the characters are folded into the running FNV-1a hash in their
 * normal left-to-right order; a '.' is folded in between labels.
 * Because FNV-1a is just a left-to-right scan, hashing "com", then
 * ".example", then ".ads", then ".x" in that order produces exactly the
 * same hash sequence as hashing the materialised string
 * "com.example.ads.x" -- so matching semantics are unchanged, while the
 * only allocation anywhere in matches() is now zero: no List, no
 * StringBuilder, no intermediate String.
 *
 * Collision risk: FNV-1a is 64-bit. Expected collisions among 150k
 * items are on the order of 1 in 10^9. Two unrelated hosts colliding
 * would cause a false block -- a real but astronomically unlikely event.
 * The trade is worth the 30x memory saving; if you ever bundle millions
 * of hosts, switch to a 128-bit hash.
 */
class HostSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    /**
     * Return true if the given host, or any of its dot-boundary suffixes,
     * was present in the blocklist this set was built from.
     *
     * The host must already be lowercase. Called from AdBlocker.check()
     * after extractHost() has normalised the URL.
     *
     * No allocation: labels are located with index arithmetic over the
     * original [host] string and hashed in place. See the class doc for
     * why this produces identical results to hashing a materialised
     * reversed string.
     */
    fun matches(host: String): Boolean {
        val n = host.length
        if (hashes.isEmpty() || n == 0) return false

        var h = FNV_OFFSET
        var labelEnd = n   // exclusive end of the label currently being scanned
        var firstLabel = true

        while (labelEnd > 0) {
            // Find the start of the right-most not-yet-processed label by
            // scanning backward for a '.' (or the start of the string).
            var labelStart = labelEnd
            while (labelStart > 0 && host[labelStart - 1] != '.') {
                labelStart--
            }

            if (!firstLabel) {
                // Fold in the separator between this label and the one
                // already hashed to its right -- equivalent to the '.'
                // that would sit between them in a reversed string.
                h = h xor DOT_CODE
                h *= FNV_PRIME
            }

            var i = labelStart
            while (i < labelEnd) {
                h = h xor host[i].code.toLong()
                h *= FNV_PRIME
                i++
            }

            // The hash so far equals fnv1a64() of the reversed-host
            // prefix built from every label walked so far -- one
            // candidate suffix. Check it exactly where the old
            // char-by-char walk checked its reversed string.
            if (containsHash(h)) return true

            firstLabel = false
            // Move past the '.' that preceded this label (if any) to
            // continue with the next label to the left. labelStart == 0
            // means we just processed the left-most label, so -1 ends
            // the loop.
            labelEnd = labelStart - 1
        }
        return false
    }

    private fun containsHash(h: Long): Boolean = hashes.binarySearch(h) >= 0

    companion object {
        val EMPTY = HostSet(LongArray(0))

        /**
         * Build a HostSet from a collection of lowercase hostnames.
         * Duplicate hashes are collapsed in place after sorting. This
         * runs once per blocklist (re)load, not on the request hot
         * path, so reverseHost()'s allocations here are fine.
         */
        fun from(hosts: Collection<String>): HostSet {
            if (hosts.isEmpty()) return EMPTY
            val raw = LongArray(hosts.size)
            var i = 0
            for (host in hosts) {
                raw[i++] = fnv1a64(reverseHost(host))
            }
            raw.sort()
            var write = 0
            for (read in raw.indices) {
                if (read == 0 || raw[read] != raw[read - 1]) {
                    raw[write++] = raw[read]
                }
            }
            val final = if (write == raw.size) raw else raw.copyOf(write)
            return HostSet(final)
        }
    }
}

// -----------------------------------------------------------------------------
// File-private helpers. Kept out of the class to make the reversal and hash
// paths easy to eyeball in one place.
// -----------------------------------------------------------------------------

/**
 * FNV-1a offset basis, 0xcbf29ce484222325 as a signed Long. Written as
 * a decimal literal because Kotlin's hex literal parser is picky about
 * values above Long.MAX_VALUE and the decimal form is unambiguous.
 */
private const val FNV_OFFSET = -3750763034362895579L

/**
 * FNV-1a 64-bit prime, 0x100000001b3.
 */
private const val FNV_PRIME = 1099511628211L

/** '.' as a Long code point, folded into the hash between labels. */
private const val DOT_CODE = '.'.code.toLong()

private fun fnv1a64(s: String): Long {
    var h = FNV_OFFSET
    for (i in s.indices) {
        h = h xor s[i].code.toLong()
        h *= FNV_PRIME
    }
    return h
}

/**
 * Reverse a dotted hostname: "ads.example.com" -> "com.example.ads".
 * Single-label hosts ("localhost") are returned unchanged. Malformed
 * inputs (empty labels, trailing dot) produce something odd but never
 * crash -- the result simply will not match anything in the set, which
 * is the correct behaviour for garbage input.
 *
 * Only used by from() when a blocklist is (re)built -- not on the
 * request hot path. See HostSet.matches() for the allocation-free
 * equivalent used there.
 */
private fun reverseHost(host: String): String {
    if (host.isEmpty()) return host
    val parts = host.split('.')
    if (parts.size == 1) return parts[0]
    val sb = StringBuilder(host.length)
    for (i in parts.indices.reversed()) {
        if (i != parts.size - 1) sb.append('.')
        sb.append(parts[i])
    }
    return sb.toString()
}
