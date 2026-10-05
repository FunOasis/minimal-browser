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
 * The suffix walk is the same trick the trie used, just expressed in
 * flat string prefixes rather than node traversal.
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
     */
    fun matches(host: String): Boolean {
        if (hashes.isEmpty() || host.isEmpty()) return false
        val reversed = reverseHost(host)
        val n = reversed.length
        var end = 0
        while (end < n) {
            val dot = reversed.indexOf('.', end)
            val cut = if (dot < 0) n else dot
            val candidate = reversed.substring(0, cut)
            if (containsHash(fnv1a64(candidate))) return true
            if (dot < 0) break
            end = dot + 1
        }
        return false
    }

    private fun containsHash(h: Long): Boolean = hashes.binarySearch(h) >= 0

    companion object {
        val EMPTY = HostSet(LongArray(0))

        /**
         * Build a HostSet from a collection of lowercase hostnames.
         * Duplicate hashes are collapsed in place after sorting.
         */
        fun from(hosts: Collection<String>): HostSet {
            if (hosts.isEmpty()) return EMPTY
            val raw = LongArray(hosts.size)
            var i = 0
            for (h in hosts) {
                raw[i++] = fnv1a64(reverseHost(h))
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
