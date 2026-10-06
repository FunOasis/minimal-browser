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
        const val MAX_TABS = 6
    }

    class Tab(val id: Long) {
        var url: String = Prefs.HOME_URL
        var title: String = ""
        var favicon: Bitmap? = null
        var webView: WebView? = null
        var lastUsedAt: Long = System.currentTimeMillis()

        // Last scroll offset reported by the WebView's OnScrollChangeListener.
        // Used by SwipeRefreshLayout's childScrollUp callback instead of the
        // unreliable WebView.canScrollVertically(-1). Volatile because the
        // listener can fire from a different thread during a fling.
        @Volatile var lastScrollY: Int = 0

        // True scroll offset reported by PageScrollProbe's JS listener.
        // Covers sites that scroll an inner div rather than the document,
        // where lastScrollY and canScrollVertically always report 0.
        val scrollBridge = ScrollStateBridge()
    }

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    private var activeIndex = 0
    private var nextId = 1L

    fun count(): Int = _tabs.size
    fun getActiveIndex(): Int = activeIndex
    fun getActive(): Tab? = _tabs.getOrNull(activeIndex)
    fun getActiveWebView(): WebView? = getActive()?.webView

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
            onFavicon(tab.favicon)
            wv.loadUrl(url)
        } else {
            wv.visibility = View.GONE
            wv.onPause()
        }

        onTabsChanged()
        return tab
    }

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

    /**
     * True if the active tab is currently scrolled away from the top.
     * SwipeRefreshLayout calls this before it starts consuming a
     * downward drag. Three sources, in order of reliability:
     *
     *   1. scrollBridge.topScroll -- the actual inner-div offset,
     *      reported by page JS. This is the only one that works on
     *      sites that use a nested scroller (manga readers, SPA
     *      shells).
     *   2. tab.lastScrollY -- the WebView's own scroll offset, tracked
     *      through OnScrollChangeListener. Reliable during flings.
     *   3. wv.canScrollVertically(-1) -- the framework call, kept as a
     *      fallback for the brief window before either of the above
     *      has a value.
     */
    fun canActiveScrollUp(): Boolean {
        val tab = getActive() ?: return false
        if (tab.scrollBridge.topScroll > 0) return true
        if (tab.lastScrollY > 0) return true
        val wv = tab.webView ?: return false
        return wv.canScrollVertically(-1)
    }

    fun findByWebView(wv: WebView): Tab? = _tabs.find { it.webView === wv }

    fun pauseAll() {
        for (tab in _tabs) {
            tab.webView?.onPause()
        }
    }

    fun resumeActive() {
        for ((i, tab) in _tabs.withIndex()) {
            val wv = tab.webView ?: continue
            if (i == activeIndex) wv.onResume() else wv.onPause()
        }
    }

    fun destroyAll() {
        for (tab in _tabs) {
            tab.webView?.let { destroyWebView(it) }
            tab.webView = null
        }
        _tabs.clear()
    }

    fun trimAllCaches() {
        for (tab in _tabs) {
            tab.webView?.clearCache(false)
        }
    }

    private fun evictForSpace() {
        while (_tabs.size >= MAX_TABS) {
            val candidate = _tabs.indices
                .filter { it != activeIndex }
                .minByOrNull { _tabs[it].lastUsedAt }
                ?: return
            destroyTabAt(candidate)
            if (candidate < activeIndex) activeIndex--
        }
    }

    private fun destroyTabAt(index: Int) {
        val tab = _tabs.removeAt(index)
        tab.webView?.url?.let { live -> if (live.isNotBlank()) tab.url = live }
        tab.webView?.let { destroyWebView(it) }
        tab.webView = null
    }

    private fun destroyWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.removeAllViews()
        wv.destroy()
    }

    private fun syncVisibility() {
        for ((i, tab) in _tabs.withIndex()) {
            val wv = tab.webView ?: continue
            if (i == activeIndex) {
                wv.visibility = View.VISIBLE
                wv.onResume()
            } else {
                wv.visibility = View.GONE
                wv.onPause()
            }
        }
    }

    private fun syncActiveUi() {
        val tab = getActive() ?: return
        val wv = tab.webView ?: return
        val url = wv.url ?: tab.url
        if (url.isNotBlank()) tab.url = url
        ui.onUrlChanged(wv, url)
        ui.onNavStateChanged(wv, wv.canGoBack(), wv.canGoForward())
        onFavicon(tab.favicon)
    }

    private fun buildWebView(tab: Tab): WebView {
        val wv = WebView(activity)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(activity.getColor(R.color.window_bg))

        // Track scroll position ourselves. WebView.canScrollVertically(-1)
        // is unreliable mid-fling and mid-layout on Chromium WebView; the
        // OnScrollChangeListener reports the actual scroll offset instead.
        // This covers normal body-scroll sites. Inner-div scrollers are
        // covered by the PageScrollProbe JS bridge below.
        wv.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            tab.lastScrollY = scrollY
        }

        // Attach the JS bridge that lets the page report its own scroll
        // offset. Must be called before any loadUrl, and the name here
        // must match PageScrollProbe.JS_INTERFACE_NAME.
        wv.addJavascriptInterface(tab.scrollBridge, PageScrollProbe.JS_INTERFACE_NAME)

        WebViewConfigurator.apply(wv, prefs.javaScriptEnabled)

        // Let the OS reclaim this renderer when the WebView isn't visible.
        // Second arg (waivedWhenNotVisible) permits the renderer to be killed
        // under memory pressure; it transparently reloads on switch back.
        runCatching {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }

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

    private inner class TabWebChromeClient(private val tab: Tab) : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            view?.url?.let { live -> if (live.isNotBlank()) tab.url = live }
            // A fresh navigation starts at scroll 0. Reset both trackers
            // here so a stale value from the previous page cannot
            // suppress pull-to-refresh on the new one.
            if (newProgress == 0) {
                tab.lastScrollY = 0
                tab.scrollBridge.reset()
            }
            if (tab === getActive()) onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            tab.title = title.orEmpty()
            view?.url?.let { live -> if (live.isNotBlank()) tab.url = live }
            if (tab === getActive()) onTabsChanged()
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            tab.favicon = icon
            if (tab === getActive()) onFavicon(icon)
        }

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
