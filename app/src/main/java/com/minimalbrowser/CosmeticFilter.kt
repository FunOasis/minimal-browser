package com.minimalbrowser

import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.net.URI

/**
 * Cosmetic filtering.
 *
 * Injects a single CSS block into the page after load. Because CSS is
 * declarative, any ad element the page injects later -- after a
 * scroll, on a timer, on an XHR response -- is hidden automatically
 * without any MutationObserver, per-element work, or runtime CPU cost.
 *
 * Rules come from one of two places:
 *
 *   1. CosmeticStore (subscription-backed). Parsed once at boot from
 *      whatever EasyList-format lists are in the cosmetic warehouse.
 *      Includes both generic and per-domain selectors.
 *
 *   2. FALLBACK_SELECTORS (built-in). Used when the cosmetic
 *      warehouse is empty -- first boot, offline, or the user cleared
 *      every list. A curated list of high-confidence ad-container
 *      selectors drawn from EasyList and AdGuard Base.
 *
 * The injected CSS is capped at MAX_CSS_BYTES. If a page's rule set
 * exceeds the cap, the earlier rules win because EasyList orders its
 * lists roughly from most to least common.
 */
object CosmeticFilter {

    private const val TAG = "CosmeticFilter"
    private const val STYLE_ID = "mb-cosmetic-filter"
    private const val MAX_CSS_BYTES = 96 * 1024

    /**
     * Curated fallback selectors. Every entry is a specific, well-known
     * ad-system string. Generic patterns like class^="ad-" are
     * deliberately absent -- they collide with legitimate page
     * furniture (.ad-tracker, .read-more-ad, etc.).
     */
    private val FALLBACK_SELECTORS: List<String> = listOf(
        "ins.adsbygoogle",
        "[id^=\"google_ads_\"]",
        "[id^=\"div-gpt-ad\"]",
        "[data-ad-slot]",
        "[data-ad-client]",
        "iframe[src*=\"doubleclick.net\"]",
        "iframe[src*=\"googlesyndication.com\"]",
        "iframe[src*=\"googleadservices.com\"]",
        "iframe[src*=\"adservice.google\"]",
        ".adsbygoogle",
        ".ad-banner",
        ".ad-container",
        ".ad-slot",
        ".ad-wrapper",
        ".advertisement",
        ".advertising",
        ".sponsored-content",
        ".sponsored-post",
        "#ad-top",
        "#ad-bottom",
        "#ad-container",
        "#advertisement",
        "#banner-ad",
        "[aria-label=\"Advertisement\"]",
        "[aria-label=\"advertisement\"]",
        "[aria-label=\"Ad\"]",
        "[id*=\"taboola\"]",
        "[class*=\"taboola\"]",
        "[id*=\"outbrain\"]",
        "[class*=\"outbrain\"]",
        ".trc_rbox",
        ".OUTBRAIN",
        "[id^=\"prebid\"]",
        "[class*=\"prebid\"]"
    )

    @Volatile private var rules: CosmeticRules? = null

    /**
     * Per-host CSS string cache. Small: page loads repeat the same host
     * many times in a browsing session, and rebuilding the selector
     * union from 20k+ strings every navigation is wasteful. Cleared
     * whenever the rule set changes.
     */
    private val cssCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>
        ): Boolean = size > 16
    }

    /**
     * Called after CosmeticStore has produced fresh raw text. Parsing
     * happens on the caller's thread -- invoke from Dispatchers.IO.
     *
     * If the incoming text yields zero usable rules we keep whatever
     * was loaded previously (including the fallback) rather than
     * blanking out the cosmetic layer entirely.
     */
    fun updateFrom(text: String) {
        if (text.isEmpty()) return
        val parsed = CosmeticRules.parse(text)
        if (parsed.genericCount == 0 && parsed.domainCount == 0) {
            Log.w(TAG, "Parsed 0 rules -- keeping previous set")
            return
        }
        rules = parsed
        synchronized(cssCache) { cssCache.clear() }
        Log.i(TAG, "Rules loaded: generic=" + parsed.genericCount +
            " domain=" + parsed.domainCount +
            " domains=" + parsed.domainKeys)
    }

    /**
     * True if subscription-backed rules have been loaded. Diagnostics
     * only -- the filter works either way.
     */
    fun hasSubscriptionRules(): Boolean = rules != null
    /**
 * Total number of cosmetic rules currently in effect. Used by the
 * ad-blocking dialog to show a legible count. Falls back to the
 * built-in selector count when the subscription layer has not yet
 * loaded anything.
 */
    fun ruleCount(): Int {    
        val current = rules    
        return if (current == null) FALLBACK_SELECTORS.size
           else current.genericCount + current.domainCount
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

        val css = cssFor(url, rules)
        if (css.isEmpty()) return

        val quoted = JSONObject.quote(css)
        val js = "(function(){" +
            "var e=document.getElementById('" + STYLE_ID + "');" +
            "if(e){e.textContent=" + quoted + ";return;}" +
            "var s=document.createElement('style');" +
            "s.id='" + STYLE_ID + "';" +
            "s.textContent=" + quoted + ";" +
            "(document.head||document.documentElement).appendChild(s);" +
            "})();"

        try {
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: " + t.message)
        }
    }

    private fun cssFor(url: String, current: CosmeticRules?): String {
        val key = hostKey(url)
        synchronized(cssCache) {
            cssCache[key]?.let { return it }
        }
        val selectors = if (current == null) FALLBACK_SELECTORS
                        else current.selectorsFor(url)
        val css = buildCss(selectors)
        synchronized(cssCache) {
            cssCache[key] = css
        }
        return css
    }

    private fun hostKey(url: String): String = try {
        URI(url).host?.lowercase() ?: url
    } catch (_: Exception) {
        url
    }

    private fun buildCss(selectors: List<String>): String {
        val sb = StringBuilder(64 * 1024)
        var first = true
        for (s in selectors) {
            if (!first) sb.append(',')
            sb.append(s)
            first = false
            if (sb.length >= MAX_CSS_BYTES) break
        }
        if (first) return ""
        sb.append("{display:none !important;visibility:hidden !important;}")
        return sb.toString()
    }
}
