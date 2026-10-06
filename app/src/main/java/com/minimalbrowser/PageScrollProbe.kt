package com.minimalbrowser

import android.util.Log
import android.webkit.WebView

/**
 * Installs a page-scoped scroll listener that reports the true scroll
 * offset to ScrollStateBridge.
 *
 * Sampling strategy. On each scroll event (capture phase, so events
 * from inner scrollers are caught too) we compute the maximum of:
 *
 *   1. document.scrollingElement.scrollTop  -- the normal body scroll
 *   2. document.body.scrollTop              -- quirks-mode docs
 *   3. scrollTop of every ancestor of the element under the viewport
 *      centre, up to 32 hops                -- inner-div scrollers
 *
 * The third case is the whole reason this class exists. Manga readers
 * and virtualised lists put all their content in a nested overflow
 * container; the top-level document stays at 0. Sampling the element
 * under the centre of the viewport is cheap and matches where the
 * reader is actually looking.
 *
 * Reporting is coalesced through requestAnimationFrame, so a fast
 * scroll hits the Java bridge at most once per frame. Values are
 * deduplicated against the last reported value before crossing the
 * bridge, so a scroll that does not change the sampled offset costs
 * nothing.
 *
 * Installation is idempotent via a window marker. Multiple
 * onPageFinished calls for the same document (redirects, hash
 * navigations) do not stack listeners.
 */
object PageScrollProbe {

    private const val TAG = "PageScrollProbe"
    private const val BRIDGE_NAME = "MBScrollBridge"

    /**
     * JS interface name as registered on the WebView. Any JS the page
     * runs can call window.MBScrollBridge.reportScrollTop(n).
     */
    const val JS_INTERFACE_NAME: String = BRIDGE_NAME

    /**
     * Installer source. Written as concatenated string literals rather
     * than a Kotlin template so the code reads top-to-bottom without
     * escaping artefacts.
     */
    val JS_INSTALLER: String by lazy {
        "(function(){" +
            "if(window.__mbScrollProbeInstalled)return;" +
            "window.__mbScrollProbeInstalled=true;" +

            "var scheduled=false;" +
            "var lastReported=-1;" +

            "function sample(){" +
                "scheduled=false;" +
                "var best=0;" +
                "try{" +
                    "var se=document.scrollingElement||document.documentElement;" +
                    "if(se&&se.scrollTop>best)best=se.scrollTop;" +
                    "if(document.body&&document.body.scrollTop>best)best=document.body.scrollTop;" +

                    "var cx=Math.floor(window.innerWidth/2);" +
                    "var cy=Math.floor(window.innerHeight/2);" +
                    "var el=document.elementFromPoint(cx,cy);" +
                    "var hops=0;" +
                    "while(el&&hops<32){" +
                        "var st=el.scrollTop;" +
                        "if(typeof st==='number'&&st>best)best=st;" +
                        "el=el.parentElement;" +
                        "hops++;" +
                    "}" +
                "}catch(e){}" +

                "best=Math.round(best);" +
                "if(best!==lastReported){" +
                    "lastReported=best;" +
                    "try{window." + BRIDGE_NAME + ".reportScrollTop(best);}catch(e){}" +
                "}" +
            "}" +

            "function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(sample);}}" +

            // Capture-phase scroll listener catches inner-scroller events.
            "window.addEventListener('scroll',schedule,true);" +

            // Re-sample right before a gesture starts, so the value
            // reflects the current visual state even if the last scroll
            // event was long ago or was suppressed.
            "window.addEventListener('touchstart',schedule,{passive:true,capture:true});" +
            "window.addEventListener('resize',schedule,true);" +

            "schedule();" +
        "})();"
    }

    /**
     * Install the probe into the given WebView. Safe to call multiple
     * times. Skips internal pages, data: URLs, and about: URLs.
     *
     * The JS bridge itself must already be registered on this WebView
     * via addJavascriptInterface before any page loads -- see
     * TabManager.buildWebView.
     */
    fun install(webView: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        if (url.startsWith(Prefs.HOME_URL)) return
        if (url.startsWith("data:")) return
        if (url.startsWith("about:")) return

        try {
            webView.evaluateJavascript(JS_INSTALLER, null)
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: " + t.message)
        }
    }
}
