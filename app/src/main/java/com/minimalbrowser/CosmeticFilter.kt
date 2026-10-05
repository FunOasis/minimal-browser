package com.minimalbrowser

import android.util.Log
import android.webkit.WebView
import org.json.JSONObject

/**
 * Cosmetic filtering.
 *
 * After a page has loaded, inject a single CSS block into the page's
 * <html> element that hides well-known ad containers by selector.
 *
 * This is a visual cleanup layer on top of AdBlocker, not a replacement
 * for it. When AdBlocker kills the network request for an ad script, the
 * page's own inline JavaScript frequently still creates an empty
 * <ins class="adsbygoogle"> or a <div id="div-gpt-ad-..."> and reserves
 * layout space for it. Blocking the request alone leaves that empty
 * rectangle visible. Hiding it with CSS makes the page look clean.
 *
 * The stylesheet rule is applied once per navigation and lives in the
 * document for the lifetime of that page. Because CSS is declarative,
 * any ad element that the page injects later (after a scroll, on a
 * timer, on an XHR response) is hidden automatically -- no
 * MutationObserver, no per-element work, no runtime CPU cost.
 *
 * The selector list is deliberately small and conservative: every entry
 * is a specific, well-known ad-system string. We avoid generic patterns
 * like [class^="ad-"] because they collide with legitimate page
 * furniture (".ad-tracker", ".lead-advisor", ".read-more-ad").
 */
object CosmeticFilter {

    private const val TAG = "CosmeticFilter"
    private const val STYLE_ID = "mb-cosmetic-filter"

    /**
     * Curated, high-confidence ad-container selectors. Drawn from the
     * public EasyList and AdGuard Base cosmetic-rule sets, filtered down
     * to entries that are safe against false positives.
     */
    private val SELECTORS: List<String> = listOf(
        // Google AdSense / Ad Exchange / Ad Manager
        "ins.adsbygoogle",
        "[id^=\"google_ads_\"]",
        "[id^=\"div-gpt-ad\"]",
        "[data-ad-slot]",
        "[data-ad-client]",
        "iframe[src*=\"doubleclick.net\"]",
        "iframe[src*=\"googlesyndication.com\"]",
        "iframe[src*=\"googleadservices.com\"]",
        "iframe[src*=\"adservice.google\"]",

        // Common explicit class names
        ".adsbygoogle",
        ".ad-banner",
        ".ad-container",
        ".ad-slot",
        ".ad-wrapper",
        ".advertisement",
        ".advertising",
        ".sponsored-content",
        ".sponsored-post",

        // Common explicit element IDs
        "#ad-top",
        "#ad-bottom",
        "#ad-container",
        "#advertisement",
        "#banner-ad",

        // ARIA-labelled ad regions
        "[aria-label=\"Advertisement\"]",
        "[aria-label=\"advertisement\"]",
        "[aria-label=\"Ad\"]",

        // Taboola / Outbrain native-ad widgets
        "[id*=\"taboola\"]",
        "[class*=\"taboola\"]",
        "[id*=\"outbrain\"]",
        "[class*=\"outbrain\"]",
        ".trc_rbox",
        ".OUTBRAIN",

        // Prebid / header-bidding wrappers
        "[id^=\"prebid\"]",
        "[class*=\"prebid\"]"
    )

    /**
     * The CSS rule body. One selector list, one declaration block. The
     * !important keeps hostile page CSS from overriding us.
     */
    private val CSS: String by lazy {
        val sb = StringBuilder(1024)
        var first = true
        for (sel in SELECTORS) {
            if (!first) sb.append(",\n")
            sb.append(sel)
            first = false
        }
        sb.append(" { display: none !important; visibility: hidden !important; }\n")
        sb.toString()
    }

    /**
     * The JS that injects the stylesheet. Built via concatenation rather
     * than a Kotlin template string to keep the source readable and to
     * avoid the chat-renderer issue with dollar-brace interpolation.
     *
     * JSONObject.quote() handles all escaping (newlines, quotes, backslashes)
     * so we never have to hand-roll it.
     *
     * Idempotent: guards on STYLE_ID so a second call to onPageFinished
     * does not create a duplicate <style> element.
     */
    private val JS: String by lazy {
        val quotedCss = JSONObject.quote(CSS)
        "(function(){" +
            "if(document.getElementById('" + STYLE_ID + "'))return;" +
            "var s=document.createElement('style');" +
            "s.id='" + STYLE_ID + "';" +
            "s.textContent=" + quotedCss + ";" +
            "(document.head||document.documentElement).appendChild(s);" +
            "})();"
    }

    /**
     * Inject the cosmetic stylesheet into the given WebView. Safe to
     * call multiple times and safe to call on any loaded page. Skips
     * internal pages (home), data: URLs, and about: URLs.
     */
    fun apply(webView: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (url.startsWith("data:")) return
        if (url.startsWith("about:")) return

        try {
            webView.evaluateJavascript(JS, null)
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: " + t.message)
        }
    }
}
