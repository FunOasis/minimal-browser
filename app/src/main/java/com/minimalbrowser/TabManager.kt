package com.minimalbrowser

import android.graphics.Bitmap
import android.os.Message
import android.util.Log
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
    private val onTabsChanged: () -> Unit,
    private val onFullscreenShow: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onFullscreenHide: () -> Unit
) {

    companion object {
        private const val TAG = "TabManager"

        const val MAX_TABS = 6

        const val FREEZE_AFTER_FG_MS = 10 * 60 * 1000L
        const val FREEZE_AFTER_BG_MS = 2 * 60 * 1000L
    }

    class Tab(val id: Long) {
        var url: String = Prefs.HOME_URL
        var title: String = ""
        var favicon: Bitmap? = null
        var webView: WebView? = null
        var lastUsedAt: Long = System.currentTimeMillis()
        var hasLoadedOnce: Boolean = false
        var frozen: Boolean = false

        /**
         * Per-tab JavaScript state. Seeded when the tab is created
         * (inherits the active tab's state, or the Prefs default if
         * there is no active tab). Changing this and reloading affects
         * only this tab.
         */
        var javaScriptEnabled: Boolean = true

        @Volatile var lastScrollY: Int = 0

        val scrollBridge = ScrollStateBridge()
    }

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    private var activeIndex = 0
    private var nextId = 1L

    private var fullscreenActive: Boolean = false

    fun count(): Int = _tabs.size
    fun getActiveIndex(): Int = activeIndex
    fun getActive(): Tab? = _tabs.getOrNull(activeIndex)
    fun getActiveWebView(): WebView? = getActive()?.webView

    fun isInFullscreen(): Boolean = fullscreenActive

    // ------------------------------------------------------------------
    // Per-tab JavaScript
    // ------------------------------------------------------------------

    /**
     * True if the active tab has JavaScript enabled. Falls back to the
     * Prefs default when there is no active tab yet.
     */
    fun isActiveJsEnabled(): Boolean =
        getActive()?.javaScriptEnabled ?: prefs.javaScriptEnabled

    /**
     * Toggle JavaScript for the active tab only. Applies the new
     * setting directly to the WebView and reloads it. Returns the new
     * state (true = on). No-op when there is no active tab.
     */
    fun toggleActiveJavaScript(): Boolean {
        val tab = getActive() ?: return prefs.javaScriptEnabled
        tab.javaScriptEnabled = !tab.javaScriptEnabled
        val wv = tab.webView
        if (wv != null) {
            wv.settings.javaScriptEnabled = tab.javaScriptEnabled
            wv.reload()
        }
        Log.i(TAG, "JS toggled for tab " + tab.id + " -> " + tab.javaScriptEnabled)
        return tab.javaScriptEnabled
    }

    // ------------------------------------------------------------------

    fun create(url: String = Prefs.HOME_URL, makeActive: Boolean = true): Tab {
        evictForSpace()

        // New tabs inherit the JS state of the tab that is active at
        // creation time. If there is no active tab (fresh start, or a
        // restore in progress), fall back to the Prefs default.
        val inheritJs = getActive()?.javaScriptEnabled ?: prefs.javaScriptEnabled

        val tab = Tab(nextId++).apply {
            this.url = url
            this.javaScriptEnabled = inheritJs
        }
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

    fun restore(urls: List<String>, activeIndex: Int) {
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
        val tab = _tabs[index]
        tab.lastUsedAt = System.currentTimeMillis()

        if (tab.frozen) {
            unfreezeTab(index)
        }

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

    fun freezeIdleTabs(isBackgrounded: Boolean): Int {
        val now = System.currentTimeMillis()
        val threshold = if (isBackgrounded) FREEZE_AFTER_BG_MS else FREEZE_AFTER_FG_MS
        var count = 0
        for ((i, tab) in _tabs.withIndex()) {
            if (tab.frozen) continue
            if (tab.webView == null) continue
            if (i == activeIndex && !isBackgrounded) continue
            val idle = now - tab.lastUsedAt
            if (idle < threshold) continue
            freezeTab(i)
            count++
        }
        return count
    }

    fun unfreezeActiveIfFrozen() {
        val idx = activeIndex
        if (idx !in _tabs.indices) return
        if (_tabs[idx].frozen) unfreezeTab(idx)
    }

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
        if (fullscreenActive) {
            fullscreenActive = false
            try { onFullscreenHide() } catch (_: Throwable) {}
        }
        for (tab in _tabs) {
            tab.webView?.let { destroyWebView(it) }
            tab.webView = null
            tab.frozen = false
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
        releaseFullscreenIfActive(index)

        val tab = _tabs.removeAt(index)
        tab.webView?.url?.let { live -> if (live.isNotBlank()) tab.url = live }
        tab.webView?.let { destroyWebView(it) }
        tab.webView = null
    }

    private fun releaseFullscreenIfActive(index: Int) {
        if (!fullscreenActive) return
        if (index != activeIndex) return
        fullscreenActive = false
        try { onFullscreenHide() } catch (t: Throwable) {
            Log.w(TAG, "releaseFullscreenIfActive host failed: " + t.message)
        }
    }

    private fun destroyWebView(wv: WebView) {
        runCatching { wv.loadUrl("about:blank") }
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.removeAllViews()
        wv.destroy()
    }

    private fun freezeTab(index: Int) {
        val tab = _tabs.getOrNull(index) ?: return
        if (tab.frozen) return
        val wv = tab.webView ?: return

        releaseFullscreenIfActive(index)

        wv.url?.let { live -> if (live.isNotBlank()) tab.url = live }

        destroyWebView(wv)

        tab.webView = null
        tab.frozen = true
        tab.hasLoadedOnce = false
        tab.lastScrollY = 0
        tab.scrollBridge.reset()

        Log.i(TAG, "Froze tab " + index + " (" + tab.url + ")")
    }

    private fun unfreezeTab(index: Int) {
        val tab = _tabs.getOrNull(index) ?: return
        if (!tab.frozen) return
        val url = tab.url.ifBlank { Prefs.HOME_URL }

        val wv = buildWebView(tab)
        tab.webView = wv
        container.addView(wv)

        tab.frozen = false
        wv.loadUrl(url)
        tab.hasLoadedOnce = true

        Log.i(TAG, "Unfroze tab " + index + " -> " + url)
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

        wv.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            tab.lastScrollY = scrollY
        }

        wv.addJavascriptInterface(tab.scrollBridge, PageScrollProbe.JS_INTERFACE_NAME)
        BlobDownloadHelper.install(wv)

        // Read JS state from the tab, not from the global Prefs. This
        // is what makes the toggle per-tab.
        WebViewConfigurator.apply(wv, tab.javaScriptEnabled)

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
                webView = wv,
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

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenActive) {
                try { callback.onCustomViewHidden() } catch (_: Throwable) {}
                return
            }
            fullscreenActive = true
            try {
                onFullscreenShow(view, callback)
            } catch (t: Throwable) {
                Log.w(TAG, "onShowCustomView host failed: " + t.message)
                fullscreenActive = false
                try { callback.onCustomViewHidden() } catch (_: Throwable) {}
            }
        }

        override fun onHideCustomView() {
            if (!fullscreenActive) return
            fullscreenActive = false
            try {
                onFullscreenHide()
            } catch (t: Throwable) {
                Log.w(TAG, "onHideCustomView host failed: " + t.message)
            }
        }
    }
}
