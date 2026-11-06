package com.minimalbrowser

import java.util.Locale

/**
 * Identifies hosts that are reached purely on the local network: a
 * numeric IP literal in a private / loopback / link-local / carrier-
 * grade-NAT range, or a ".local" mDNS name. This is exactly the set of
 * addresses a home router, an ISP-supplied modem/ONT, or a local
 * gateway's admin page is reached at:
 *
 *   192.168.x.x        -- the overwhelming majority of home routers
 *   10.x.x.x           -- business gear and some ISP ONT/modem pages
 *   172.16.0.0-31.x.x  -- ditto
 *   169.254.x.x        -- link-local, used by a few ISP modems before
 *                          a DHCP lease exists
 *   100.64.0.0/10      -- carrier-grade NAT, some ISPs put the modem's
 *                          own admin page here
 *   127.x.x.x / ::1    -- loopback
 *   fe80::/10, fc00::/7, fd00::/8 -- IPv6 link-local / unique-local
 *   *.local            -- mDNS names (router.local, orbi.local, ...);
 *                          ".local" is reserved by RFC 6762 and is
 *                          never resolvable on the public internet
 *
 * Deliberately numeric-literal (plus .local) only: resolving an
 * arbitrary hostname would mean a DNS lookup from inside a
 * WebViewClient callback running on the UI thread, and keying a
 * browser-wide policy relaxation off a *name* (which a remote site or
 * redirect controls) instead of the literal address the user actually
 * typed or was redirected to by their own gateway is a bigger attack
 * surface than this feature needs.
 */
object LocalNetwork {

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isLocalOrPrivateHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim(' ', '[', ']').lowercase(Locale.ROOT)
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        if (h.endsWith(".local")) return true
        return isPrivateIpv4(h) || isPrivateIpv6(h)
    }

    private fun isPrivateIpv4(h: String): Boolean {
        val m = IPV4.matchEntire(h) ?: return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val v = m.groupValues[i + 1].toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(h: String): Boolean {
        if (h == "::1") return true
        return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    }
}
