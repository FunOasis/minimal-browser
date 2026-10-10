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
 * Rule source: CosmeticStore. Nothing is hardcoded. On a fresh install
 * the cosmetic warehouse is empty, CosmeticFilter holds no rules, and
 * the cosmetic layer does nothing until the user supplies a
 * subscription URL through a future UI. This is deliberate: the APK
 * ships with zero ad-blocking data.
 */
object CosmeticFilter {

    private const val TAG = "CosmeticFilter"
    private const val STYLE_ID = "mb-cosmetic-filter"
    private const val MAX_CSS_BYTES = 96 * 1024

    /**
     * Empty by design. Earlier builds shipped a curated fallback list
     * here; that data now lives only in user-supplied subscriptions.
     */
    private val FALLBACK_SELECTORS: List<String> = emptyList()

    @Volatile private var rules: CosmeticRules? = null

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
     * was loaded previously rather than blanking the layer.
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
     * True if subscription-backed rules have been loaded.
     */
    fun hasSubscriptionRules(): Boolean = rules != null

    /**
     * Total number of cosmetic rules currently in effect. Zero when
     * the warehouse is empty and no subscription has been loaded.
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
     *
     * No-op when there are no rules to inject.
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
        if (selectors.isEmpty()) return ""
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
