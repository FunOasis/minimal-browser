package com.minimalbrowser

import android.webkit.JavascriptInterface

/**
 * Native end of the page-scroll reporting bridge.
 *
 * Many sites -- manga/comic readers, virtualised lists, SPA shells --
 * scroll an inner div rather than the document. WebView.scrollY and
 * WebView.canScrollVertically(-1) only reflect the top-level document,
 * so on those sites they always report "at top" even when the visible
 * content is scrolled far down. SwipeRefreshLayout then thinks the
 * page cannot scroll up and steals the downward drag, firing the
 * spinner mid-article.
 *
 * This class stores the true topmost scroll offset, computed by the
 * page's JS (see PageScrollProbe) and pushed across a
 * @JavascriptInterface method. Written from the WebView JavaBridge
 * thread, read from the UI thread during touch interception -- a
 * plain @Volatile Int is sufficient.
 *
 * Threat model: the interface is write-only and the value is clamped.
 * A hostile page can at worst force the pull-to-refresh suppressor on
 * itself. It cannot read anything, exfiltrate anything, or affect
 * other tabs.
 */
class ScrollStateBridge {

    @Volatile
    var topScroll: Int = 0

    /**
     * Called from page JS whenever the effective scroll position
     * changes. The value is clamped to a small non-negative range so
     * a compromised or buggy page cannot wedge the layout in either
     * direction.
     */
    @JavascriptInterface
    fun reportScrollTop(value: Int) {
        topScroll = when {
            value < 0 -> 0
            value > 1_000_000 -> 1_000_000
            else -> value
        }
    }

    fun reset() {
        topScroll = 0
    }
}
