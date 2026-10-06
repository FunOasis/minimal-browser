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

        // True once a load has been issued for this tab. Used by switchTo
        // to lazily load a tab that was created in the background (either
        // as a restored tab from saved state, or as a non-active tab from
        // restore()). Prevents a failed load from being retried on every
        // tab switch.
        var hasLoadedOnce: Boolean = false

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
            tab.hasLoadedOnce = true
        } else {
            wv.visibility = View.GONE
            wv.onPause()
        }

        onTabsChanged()
        return tab
    }

    /**
     * Rebuild the tab set from saved state. Called from MainActivity's
     * onCreate when Android is recreating the activity with a bundle
     * (config change we do not handle, or process death after being
     * backgrounded). Only the saved active tab is loaded immediately;
     * the others stay dormant until tapped.
     *
     * Contract for what survives:
     *   preserved  -- the list of URLs, and which one was active
     *   preserved  -- cookies, localStorage, HTTP cache (WebView-owned
     *                 on-disk data, independent of this bundle)
     *   lost       -- in-memory JS state, form field contents, scroll
     *                 position, back/forward history, scroll offsets
     *
     * Same guarantees Chrome and Firefox give after process death.
     *
     * Input is treated as hostile: a saved bundle can carry junk if a
     * page briefly had an exotic URL in the main frame. We accept only
     * http, https, and minimal:// (home). Anything else is dropped.
     * The count is capped at MAX_TABS. If every URL is dropped, or the
     * list was empty to begin with, we fall back to a single home tab
     * so the activity is never left without a WebView.
     */
    fun restore(urls: List<String>, activeIndex: Int) {
        // Defensive: if somehow we already have tabs (should not happen
        // in the current lifecycle, but a future refactor could call
        // restore from a different path), tear them down first so we
        // do not stack two sets of WebViews.
        if (_tabs.isNotEmpty()) {
            destroyAll()
        }

        val cleaned = ArrayList<String>(urls.size)
        for (raw in urls) {
            val u = raw.trim()
            if (u.isEmpty()) continue
            if (!isRestorable(u)) continue
            cleaned.add(u)
            if (cleaned.size >= MAX_TABS) break
        }

        if (cleaned.isEmpty()) {
            create()
            return
        }

        for (u in cleaned) {
            create(url = u, makeActive = false)
        }
        switchTo(activeIndex.coerceIn(0, _tabs.size - 1))
    }

    /**
     * Which URLs are safe to feed back into loadUrl on next launch.
     * http and https are obvious. minimal://home is our own internal
     * page and should restore. Everything else -- about:, data:, blob:,
     * javascript:, content://, file:// -- is excluded. A user who was
     * on one of those when the process died does not meaningfully miss
     * it, and restoring a script: URL into a fresh WebView is exactly
     * the class of thing we do not want on our hands.
     */
    private fun isRestorable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("minimal://")
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

        // A tab created with makeActive=false (restored from saved state,
        // or pre-created as a background tab) has never loaded a URL.
        // Load on first activation so it becomes usable. Once loaded,
        // subsequent switches are pure visibility toggles with no reload.
        val tab = _tabs[index]
        val wv = tab.webView
        if (wv != null && !tab.hasLoadedOnce && tab.url.isNotBlank()) {
            wv.loadUrl(tab.url)
            tab.hasLoadedOnce = true
        }

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

    /**
     * Release a WebView's resources. Blank the page first so the
     * renderer drops its document and native bitmap caches before
     * WebView.destroy() tears down the process bridge. Without the
     * blank, destroy() can leave native memory pinned until the OS
     * reclaims it, which matters when the LRU is evicting a tab under
     * memory pressure.
     */
    private fun destroyWebView(wv: WebView) {
        runCatching { wv.loadUrl("about:blank") }
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
                        // Always dispose the trap WebView, regardless of
                        // whether a usable URL arrived. A page can open a
                        // window and then cancel or navigate to nothing;
                        // without this the trap would leak native resources
                        // until process death.
                        v?.post { runCatching { v.destroy() } }
                        if (url.isNotBlank()) create(url = url, makeActive = true)
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
