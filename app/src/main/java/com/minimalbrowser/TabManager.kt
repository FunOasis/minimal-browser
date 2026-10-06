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

        /**
         * Freeze thresholds. A tab is a candidate for freezing when it is
         * not the active tab and has not been the active tab for this long.
         * Frozen means: WebView destroyed, Tab object retained, URL kept.
         */
        const val FREEZE_AFTER_FG_MS = 10 * 60 * 1000L   // 10 min foreground
        const val FREEZE_AFTER_BG_MS = 2 * 60 * 1000L    // 2 min background
    }

    class Tab(val id: Long) {
        var url: String = Prefs.HOME_URL
        var title: String = ""
        var favicon: Bitmap? = null
        var webView: WebView? = null
        var lastUsedAt: Long = System.currentTimeMillis()

        // True once a load has been issued for this tab. Prevents a failed
        // load from being retried on every tab switch.
        var hasLoadedOnce: Boolean = false

        // True when the WebView has been released to save CPU and battery.
        // The tab still exists, still shows in the tab sheet, still has a
        // URL. switchTo() rebuilds the WebView lazily.
        var frozen: Boolean = false

        // Last scroll offset reported by the WebView's OnScrollChangeListener.
        // Volatile because the listener can fire from a different thread.
        @Volatile var lastScrollY: Int = 0

        // True scroll offset reported by PageScrollProbe's JS listener.
        // Covers sites that scroll an inner div rather than the document.
        val scrollBridge = ScrollStateBridge()
    }

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    private var activeIndex = 0
    private var nextId = 1L

    // True between onShowCustomView and onHideCustomView. Guards against
    // a second onShowCustomView firing while we are already showing a
    // custom view, and lets destroyAll() know whether the host needs to
    // be told to restore its chrome.
    private var fullscreenActive: Boolean = false

    fun count(): Int = _tabs.size
    fun getActiveIndex(): Int = activeIndex
    fun getActive(): Tab? = _tabs.getOrNull(activeIndex)
    fun getActiveWebView(): WebView? = getActive()?.webView

    /**
     * True while a page is showing a custom fullscreen view (video,
     * manga reader, generic Fullscreen API call). The host uses this to
     * decide whether Back should exit fullscreen instead of navigating.
     */
    fun isInFullscreen(): Boolean = fullscreenActive

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
     * onCreate when Android is recreating the activity with a bundle or
     * a disk snapshot. Only the saved active tab loads immediately; the
     * others stay dormant until tapped.
     *
     * Input is treated as hostile: a saved bundle can carry junk if a
     * page briefly had an exotic URL in the main frame. Only http, https,
     * and minimal:// (home) survive. Count capped at MAX_TABS.
     */
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

        // If the tab was frozen to save battery, rebuild it now. This is
        // the only place frozen tabs come back to life during normal use.
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

    /**
     * Release the WebView of every tab that has been idle past the
     * threshold. Called periodically from MainActivity's scheduler.
     *
     * Foreground threshold is generous (10 min) so a user who is actively
     * switching between tabs never trips it. A tab opened an hour ago and
     * forgotten does.
     *
     * Background threshold is aggressive (2 min). When the app is not
     * visible, every CPU cycle a background tab burns is pure waste.
     * Freezing the active tab in background too, at the background
     * threshold, is deliberate: the user is not looking at anything, so
     * there is no reason to keep a live renderer.
     *
     * Returns the number of tabs frozen this round, for logging.
     */
    fun freezeIdleTabs(isBackgrounded: Boolean): Int {
        val now = System.currentTimeMillis()
        val threshold = if (isBackgrounded) FREEZE_AFTER_BG_MS else FREEZE_AFTER_FG_MS
        var count = 0
        for ((i, tab) in _tabs.withIndex()) {
            if (tab.frozen) continue
            if (tab.webView == null) continue
            // In foreground, never freeze the active tab. In background,
            // freeze it too.
            if (i == activeIndex && !isBackgrounded) continue
            val idle = now - tab.lastUsedAt
            if (idle < threshold) continue
            freezeTab(i)
            count++
        }
        return count
    }

    /**
     * Rebuild the active tab if it was frozen while backgrounded. Called
     * from MainActivity.onResume so the user sees the page they left,
     * not a blank view.
     */
    fun unfreezeActiveIfFrozen() {
        val idx = activeIndex
        if (idx !in _tabs.indices) return
        if (_tabs[idx].frozen) unfreezeTab(idx)
    }

    /**
     * True if the active tab is currently scrolled away from the top.
     * Three sources, in order of reliability: inner-div offset from the
     * page JS bridge, then the WebView's own offset, then the framework
     * call as a last resort.
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
        // If a page is currently showing a custom fullscreen view, tell
        // the host to restore its chrome before we tear the tabs down.
        // Otherwise the toolbar stays hidden after the tabs are gone.
        if (fullscreenActive) {
            fullscreenActive = false
            try {
                onFullscreenHide()
            } catch (_: Throwable) {
                // ignore
            }
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
        val tab = _tabs.removeAt(index)
        tab.webView?.url?.let { live -> if (live.isNotBlank()) tab.url = live }
        tab.webView?.let { destroyWebView(it) }
        tab.webView = null
    }

    /**
     * Release a WebView's resources. Blank the page first so the renderer
     * drops its document and native bitmap caches before destroy() tears
     * down the process bridge.
     */
    private fun destroyWebView(wv: WebView) {
        runCatching { wv.loadUrl("about:blank") }
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.removeAllViews()
        wv.destroy()
    }

    /**
     * Freeze a tab: destroy the WebView but keep the Tab object intact.
     * The tab stays visible in the tab sheet with its cached title and
     * favicon. The URL is what it will reload from on the next switch.
     */
    private fun freezeTab(index: Int) {
        val tab = _tabs.getOrNull(index) ?: return
        if (tab.frozen) return
        val wv = tab.webView ?: return

        // Capture the live URL before dropping the view. The WebView may
        // have navigated since we last saw it.
        wv.url?.let { live -> if (live.isNotBlank()) tab.url = live }

        // Release the bridge and the renderer.
        destroyWebView(wv)

        tab.webView = null
        tab.frozen = true
        tab.hasLoadedOnce = false
        tab.lastScrollY = 0
        tab.scrollBridge.reset()

        Log.i(TAG, "Froze tab " + index + " (" + tab.url + ")")
    }

    /**
     * Unfreeze a tab: rebuild its WebView and reload the saved URL. Called
     * from switchTo or from unfreezeActiveIfFrozen after returning from
     * the background.
     */
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

        // Attach the blob-download bridge so pages that call
        // URL.createObjectURL + a[download] can hand us the bytes.
        BlobDownloadHelper.install(wv)

        WebViewConfigurator.apply(wv, prefs.javaScriptEnabled)

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
                        // Always dispose the trap WebView, regardless of
                        // whether a usable URL arrived.
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

        /**
         * Fullscreen API entry. Sites that call requestFullscreen() --
         * video players, manga readers, PDF viewers, generic "full screen"
         * buttons -- end up here. WebView hands us a View containing the
         * fullscreen content. We forward it to MainActivity, which
         * attaches it above the toolbar, hides the chrome, and enables
         * immersive system UI.
         *
         * Guarded against reentry: some sites fire requestFullscreen()
         * twice in a row. If we are already showing a custom view, the
         * second request is rejected and its callback immediately
         * dismissed, which matches Chrome's behaviour.
         */
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

        /**
         * Fullscreen API exit. Either the site called exitFullscreen(),
         * or MainActivity signalled the callback to take us out (Back
         * button). Either way, WebView routes here for the cleanup.
         */
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
