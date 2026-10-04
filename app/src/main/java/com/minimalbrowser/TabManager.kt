package com.minimalbrowser

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Owns every WebView in the browser session.
 *
 * Architecture: N WebViews live inside a single FrameLayout container; only
 * the active one is VISIBLE. Switching tabs is a visibility toggle, so pages
 * keep their DOM, scroll position, and history live while inactive.
 *
 * Eviction: capped at MAX_TABS (6) live tabs. When a new tab is created past
 * the cap, the least-recently-used *non-active* tab is fully closed (WebView
 * destroyed, entry removed). This is LRU, not FIFO: if you bounce back to an
 * old tab before opening a new one, the tab you last used longest ago gets
 * evicted, not necessarily the oldest.
 *
 * Threading: all methods must be called from the main thread (WebView
 * construction and destroy are main-thread-only operations).
 */
class TabManager(
    private val activity: MainActivity,
    private val container: FrameLayout,
    private val prefs: Prefs,
    private val blocker: AdBlocker,
    private val ui: BrowserUiListener,
    private val onProgress: (Int) -> Unit,
    private val onFavicon: (Bitmap?) -> Unit,
    private val onTabsChanged: () -> Unit
) {

    companion object {
        /** Live-tab cap. LRU eviction fires when a new tab would exceed this. */
        const val MAX_TABS = 6
    }

    class Tab(val id: Long) {
        var url: String = Prefs.HOME_URL
        var title: String = ""
        var favicon: Bitmap? = null
        var webView: WebView? = null
        var lastUsedAt: Long = System.currentTimeMillis()
    }

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    private var activeIndex = 0
    private var nextId = 1L

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun count(): Int = _tabs.size
    fun getActiveIndex(): Int = activeIndex
    fun getActive(): Tab? = _tabs.getOrNull(activeIndex)
    fun getActiveWebView(): WebView? = getActive()?.webView

    /**
     * Creates a new tab, loads url in it, and (by default) makes it active.
     * If the live-tab cap is reached, the LRU non-active tab is closed first.
     */
    fun create(url: String = Prefs.HOME_URL, makeActive: Boolean = true): Tab {
        evictForSpace()

        val tab = Tab(nextId++).apply { this.url = url }
        val wv = buildWebView(tab)
        tab.webView = wv
        container.addView(wv)
        _tabs.add(tab)

        if (makeActive) {
            activeIndex = _tabs.size - 1
            tab.lastUsedAt = System.currentTimeMillis()
            syncVisibility()
            ui.onUrlChanged(wv, url)
            ui.onNavStateChanged(wv, false, false)
            wv.loadUrl(url)
        } else {
            wv.visibility = View.GONE
        }

        onTabsChanged()
        return tab
    }

    /**
     * Closes the tab at index. Refuses to close the last remaining tab — the
     * caller (MainActivity) is responsible for showing the exit confirmation
     * when only one tab remains and back is pressed with no history left.
     */
    fun closeTab(index: Int) {
        if (index !in _tabs.indices) return
        if (_tabs.size <= 1) return

        destroyTabAt(index)

        when {
            index == activeIndex ->
                activeIndex = if (index > 0) index - 1 else 0
            index < activeIndex ->
                activeIndex--
        }

        syncVisibility()
        onTabsChanged()
        syncActiveUi()
    }

    fun switchTo(index: Int) {
        if (index !in _tabs.indices) return
        activeIndex = index
        _tabs[index].lastUsedAt = System.currentTimeMillis()
        syncVisibility()
        onTabsChanged()
        syncActiveUi()
    }

    fun reloadActive() {
        getActiveWebView()?.reload()
    }

    fun findByWebView(wv: WebView): Tab? = _tabs.find { it.webView === wv }

    fun destroyAll() {
        for (tab in _tabs) {
            tab.webView?.let { destroyWebView(it) }
            tab.webView = null
        }
        _tabs.clear()
    }

    /** Called by MainActivity.onTrimMemory — frees each WebView's disk cache. */
    fun trimAllCaches() {
        for (tab in _tabs) {
            tab.webView?.clearCache(false)
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun evictForSpace() {
        while (_tabs.size >= MAX_TABS) {
            val candidate = _tabs.indices
                .filter { it != activeIndex }
                .minByOrNull { _tabs[it].lastUsedAt }
                ?: return  // only one tab and we can't evict it; cap can't be reached
            destroyTabAt(candidate)
            if (candidate < activeIndex) activeIndex--
        }
    }

    private fun destroyTabAt(index: Int) {
        val tab = _tabs.removeAt(index)
        tab.webView?.let { destroyWebView(it) }
        tab.webView = null
    }

    private fun destroyWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.loadUrl("about:blank")
        wv.removeAllViews()
        wv.destroy()
    }

    private fun syncVisibility() {
        for ((i, tab) in _tabs.withIndex()) {
            tab.webView?.visibility = if (i == activeIndex) View.VISIBLE else View.GONE
        }
    }

    /** Pushes the active tab's URL and nav state into the address bar / buttons. */
    private fun syncActiveUi() {
        val tab = getActive() ?: return
        val wv = tab.webView ?: return
        val url = wv.url ?: tab.url
        ui.onUrlChanged(wv, url)
        ui.onNavStateChanged(wv, wv.canGoBack(), wv.canGoForward())
    }

    private fun buildWebView(tab: Tab): WebView {
        val wv = WebView(activity)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(activity.getColor(R.color.window_bg))

        WebViewConfigurator.apply(wv, prefs.javaScriptEnabled)

        wv.webViewClient = BlockingWebViewClient(
            blocker = blocker,
            appContext = activity.applicationContext,
            ui = ui
        )
        wv.webChromeClient = TabWebChromeClient(tab)

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            DownloadHandler.handle(
                activity = activity,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength
            )
        }

        return wv
    }

    /**
     * Per-tab WebChromeClient. Feeds title/favicon/progress into the owning
     * Tab, and notifies MainActivity only when the callback comes from the
     * active tab. Also intercepts target=_blank via onCreateWindow.
     */
    private inner class TabWebChromeClient(private val tab: Tab) : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (tab === getActive()) onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            tab.title = title.orEmpty()
            if (tab === getActive()) onTabsChanged()
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            tab.favicon = icon
            if (tab === getActive()) onFavicon(icon)
        }

        /**
         * target=_blank and window.open() land here. Chromium hands us a
         * Message containing a WebViewTransport; we supply a throwaway
         * WebView as the transport target, which then immediately fires
         * shouldOverrideUrlLoading with the real URL. We intercept there,
         * open a proper new tab, and never let the throwaway render.
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val msg = resultMsg ?: return false
            val trap = WebView(activity).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (url.isNotBlank()) create(url = url, makeActive = true)
                        v?.post { runCatching { v.destroy() } }
                        return true
                    }
                }
            }
            (msg.obj as? WebView.WebViewTransport)?.webView = trap
            msg.sendToTarget()
            return true
        }
    }
}
