package com.minimalbrowser

/**
 * Strips known tracking query parameters from URLs before they load.
 *
 * Called from two places:
 *   - BlockingWebViewClient.shouldOverrideUrlLoading, for any main-frame
 *     http(s) navigation the page initiates (link clicks, JS
 *     location.href assignments).
 *   - TabManager.loadActive, for programmatic loads (address bar,
 *     suggestions, shortcuts).
 *
 * Only applies to main-frame navigations. Subresource requests are left
 * alone: tracking params on images or scripts are already handled by
 * host/pattern blocking, and stripping them there breaks nothing worth
 * the risk of breaking signatures on CDN URLs.
 *
 * Signed URLs (GitHub raw with ?token=..., S3 presigned) are NOT
 * affected because the parameters we strip are on the DEFAULT list;
 * "token" and "X-Amz-*" are not. If a user ever hits a conflict they
 * can whitelist the host via the site-blocking toggle.
 *
 * Implementation is pure string manipulation -- no URI reconstruction,
 * which would risk mangling already-encoded components.
 */
object UrlCleaner {

    /**
     * Tracking parameters stripped by default. Drawn from uBO's
     * removeparam list plus a handful of common analytics IDs.
     */
    val DEFAULT_REMOVE_PARAMS: Set<String> = setOf(
        // Google Analytics / Ads
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_cid", "utm_reader", "utm_referrer",
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "_ga", "_gl",
        // Facebook
        "fbclid",
        // Mailchimp
        "mc_eid", "mc_cid",
        // Microsoft
        "msclkid",
        // Yandex
        "yclid",
        // Twitter
        "twclid",
        // Instagram
        "igshid",
        // HubSpot
        "_hsenc", "_hsmi",
        // Amazon / Adobe / Marketo / misc
        "s_kwcid", "mkt_tok",
        "vero_conv", "vero_id",
        "oly_enc_id", "oly_anon_id"
    )

    /**
     * Remove any query parameter whose (lowercased) name appears in
     * params. Returns the original string unchanged if nothing matched.
     * Order of remaining parameters is preserved.
     */
    fun clean(url: String, params: Set<String>): String {
        if (params.isEmpty()) return url
        val q = url.indexOf('?')
        if (q < 0 || q == url.length - 1) return url

        val hash = url.indexOf('#', q)
        val path = url.substring(0, q)
        val query = if (hash < 0) url.substring(q + 1) else url.substring(q + 1, hash)
        val frag = if (hash < 0) "" else url.substring(hash)

        val parts = query.split('&')
        val kept = parts.filterNot { p ->
            val key = p.substringBefore('=').lowercase()
            key in params
        }
        if (kept.size == parts.size) return url

        val newQuery = kept.joinToString("&")
        return if (newQuery.isEmpty()) path + frag
               else path + "?" + newQuery + frag
    }
}
