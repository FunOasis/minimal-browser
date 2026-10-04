package com.minimalbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityMainBinding
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), BrowserUiListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var blocker: AdBlocker
    private lateinit var prefs: Prefs
    private lateinit var tabManager: TabManager

    private var lastSeenTabId: Long = -1L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        blocker = AdBlocker.get(this)
        prefs   = Prefs.get(this)

        tabManager = TabManager(
            activity = this,
            container = binding.webViewContainer,
            prefs = prefs,
            blocker = blocker,
            ui = this,
            onProgress = { p ->
                binding.progressBar.progress = p
                binding.progressBar.visibility =
                    if (p in 1..99) View.VISIBLE else View.GONE
                if (p == 100) binding.swipeRefresh.isRefreshing = false
            },
            onFavicon = { icon ->
                if (icon == null) {
                    binding.favicon.setImageDrawable(null)
                    binding.favicon.visibility = View.GONE
                } else {
                    binding.favicon.setImageBitmap(icon)
                    binding.favicon.visibility = View.VISIBLE
                }
            },
            onTabsChanged = { updateTabBadge() }
        )

        binding.swipeRefresh.setOnRefreshListener { tabManager.reloadActive() }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val wv = tabManager.getActiveWebView() ?: return@setOnChildScrollUpCallback false
            wv.canScrollVertically(-1)
        }

        binding.btnBack.setOnClickListener {
            val wv = tabManager.getActiveWebView()
            if (wv != null && wv.canGoBack()) wv.goBack()
        }
        binding.btnForward.setOnClickListener {
            val wv = tabManager.getActiveWebView()
            if (wv != null && wv.canGoForward()) wv.goForward()
        }
        binding.btnMenu.setOnClickListener { showOverflowMenu(it) }
        binding.tabBadge.setOnClickListener { showTabSwitcher() }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) { navigate(); true } else false
        }

        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.addressBar.post { binding.addressBar.selectAll() }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = tabManager.getActiveWebView()
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                    return
                }
                if (tabManager.count() > 1) {
                    tabManager.closeTab(tabManager.getActiveIndex())
                    return
                }
                showExitConfirm()
            }
        })

        val shortcutUrl = intent?.getStringExtra(EXTRA_SHORTCUT_URL)
        if (!shortcutUrl.isNullOrBlank()) {
            intent.removeExtra(EXTRA_SHORTCUT_URL)
            tabManager.create(url = shortcutUrl)
        } else {
            tabManager.create()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_SHORTCUT_URL)
        intent.removeExtra(EXTRA_SHORTCUT_URL)
        if (!url.isNullOrBlank()) {
            val active = tabManager.getActiveWebView()
            if (active != null) {
                active.loadUrl(url)
                binding.addressBar.setText(displayUrl(url))
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tabManager.trimAllCaches()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DownloadHandler.onPermissionResult(this, requestCode, grantResults)
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------

    private fun showOverflowMenu(anchor: View) {
        val themedContext = ContextThemeWrapper(
            this,
            R.style.ThemeOverlay_MinimalBrowser_PopupMenu
        )
        val popup = PopupMenu(themedContext, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)

        val tabsItem = popup.menu.findItem(R.id.action_tabs)
        tabsItem?.title = getString(R.string.menu_tabs, tabManager.count())

        popup.setOnMenuItemClickListener { item -> handleMenu(item) }
        popup.show()
    }

    private fun handleMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_home -> { loadHome(); true }

        R.id.action_new_tab -> {
            tabManager.create()
            true
        }

        R.id.action_tabs -> {
            showTabSwitcher()
            true
        }

        R.id.action_add_shortcut -> {
            addCurrentPageShortcut()
            true
        }

        R.id.action_js -> {
            prefs.javaScriptEnabled = !prefs.javaScriptEnabled
            tabManager.tabs.forEach { tab ->
                tab.webView?.settings?.javaScriptEnabled = prefs.javaScriptEnabled
                tab.webView?.reload()
            }
            Toast.makeText(
                this,
                "JavaScript: " + (if (prefs.javaScriptEnabled) "ON" else "OFF"),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        R.id.action_custom_filters -> {
            showCustomFiltersDialog()
            true
        }

        R.id.action_downloads -> {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_SHORT).show()
            }
            true
        }

        R.id.action_clear -> {
            tabManager.tabs.forEach { tab ->
                tab.webView?.clearHistory()
                tab.webView?.clearCache(true)
            }
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
            true
        }

        R.id.action_exit -> {
            finishAndRemoveTask()
            true
        }

        else -> false
    }

    // ---------------------------------------------------------------------
    // Tab switcher / exit confirm / custom filters / shortcut
    // ---------------------------------------------------------------------

    private fun showTabSwitcher() {
        TabSwitcherSheet(this, tabManager) { visible ->
            if (visible) {
                binding.footerText.visibility = View.VISIBLE
            } else {
                val url = tabManager.getActiveWebView()?.url ?: ""
                updateFooterFor(url)
            }
        }.show()
    }

    private fun updateTabBadge() {
        val active = tabManager.getActiveIndex() + 1
        val total = tabManager.count()
        binding.tabBadge.text = "$active/$total"
    }

    private fun updateFooterFor(url: String) {
        binding.footerText.visibility =
            if (url.startsWith(Prefs.HOME_URL)) View.VISIBLE else View.GONE
    }

    private fun showExitConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> finishAndRemoveTask() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addCurrentPageShortcut() {
        val wv = tabManager.getActiveWebView() ?: return
        val url = wv.url
        if (url.isNullOrBlank() || url.startsWith("minimal://")) {
            Toast.makeText(this, R.string.shortcut_needs_page, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = tabManager.findByWebView(wv)
        val result = ShortcutHelper.requestPin(
            context = this,
            url = url,
            title = (tab?.title ?: wv.title).orEmpty(),
            favicon = tab?.favicon
        )
        val msgRes = when (result) {
            ShortcutHelper.Result.PINNED      -> R.string.shortcut_requested
            ShortcutHelper.Result.UNSUPPORTED -> R.string.shortcut_unsupported
            ShortcutHelper.Result.INVALID     -> R.string.shortcut_needs_page
        }
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    private fun showCustomFiltersDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_filters, null)
        val editBlocklist = view.findViewById<EditText>(R.id.editBlocklist)
        val editPatterns  = view.findViewById<EditText>(R.id.editPatterns)
        val statsView     = view.findViewById<TextView>(R.id.customFilterStats)

        editBlocklist.setText(prefs.customBlocklist)
        editPatterns.setText(prefs.customFilters)

        val s = blocker.stats()
        statsView.text = getString(R.string.custom_filters_stats, s.hosts, s.patterns)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_custom_filters)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                prefs.customBlocklist = editBlocklist.text.toString()
                prefs.customFilters   = editPatterns.text.toString()
                blocker.reloadCustomRules()
                Toast.makeText(this, R.string.custom_filters_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                prefs.customBlocklist = ""
                prefs.customFilters   = ""
                blocker.reloadCustomRules()
                Toast.makeText(this, R.string.custom_filters_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // BrowserUiListener — all callbacks carry the originating WebView so we
    // can ignore events from inactive tabs.
    // ---------------------------------------------------------------------

    override fun onUrlChanged(view: WebView, url: String) {
        if (view !== tabManager.getActiveWebView()) return
        updateFooterFor(url)

        val currentTabId = tabManager.getActive()?.id ?: -1L
        val tabChanged = currentTabId != lastSeenTabId
        lastSeenTabId = currentTabId

        if (binding.addressBar.hasFocus() && !tabChanged) return
        binding.addressBar.setText(displayUrl(url))
    }

    override fun onNavStateChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean) {
        if (view !== tabManager.getActiveWebView()) return
        binding.btnBack.isEnabled    = canGoBack
        binding.btnForward.isEnabled = canGoForward
    }

    override fun onPageLoadStarted(view: WebView) {
        if (view !== tabManager.getActiveWebView()) return
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onPageLoadFinished(view: WebView) {
        if (view !== tabManager.getActiveWebView()) return
        binding.swipeRefresh.isRefreshing = false
    }

    override fun onPageLoadError(view: WebView?, description: String, url: String?) {
        if (view != null && view !== tabManager.getActiveWebView()) return
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.favicon.setImageDrawable(null)
        binding.favicon.visibility = View.GONE
        view?.loadDataWithBaseURL(
            null, errorPageHtml(description, url), "text/html", "utf-8", null
        )
    }

    override fun onRenderProcessGone(view: WebView) {
        Toast.makeText(this, "Renderer crashed - restarting", Toast.LENGTH_SHORT).show()
        recreate()
    }

    override fun onDownloadRequested(view: WebView, url: String) {
        DownloadHandler.handle(
            activity = this,
            url = url,
            userAgent = view.settings.userAgentString,
            contentDisposition = null,
            mimeType = null,
            contentLength = -1L
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun loadHome() {
        val wv = tabManager.getActiveWebView() ?: return
        binding.addressBar.setText("")
        wv.loadUrl(Prefs.HOME_URL)
    }

    private fun displayUrl(url: String): String =
        if (url.startsWith("minimal://")) "" else url

    private fun navigate() {
        val wv = tabManager.getActiveWebView() ?: return
        val input = binding.addressBar.text.toString().trim()
        if (input.isEmpty()) return
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        wv.loadUrl(normalize(input))
        binding.addressBar.clearFocus()
    }

    private fun normalize(input: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(" ") || !input.contains(".") ->
            "https://search.brave.com/search?q=" + URLEncoder.encode(input, "UTF-8")
        else -> "https://$input"
    }

    private fun errorPageHtml(description: String, url: String?): String {
        val safeDesc = description.replace("<", "&lt;").replace("&", "&amp;")
        val safeUrl  = (url ?: "").replace("<", "&lt;").replace("&", "&amp;")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; background: #0A0A0A; }
              body { font-family: -apple-system, system-ui, sans-serif;
                     color: #FFFFFF; padding: 64px 24px; text-align: center; }
              h1 { font-size: 20px; font-weight: 600; margin: 0 0 8px; }
              p  { font-size: 15px; color: #8A8A8A; margin: 0 0 4px; }
              .url { font-size: 12px; color: #666666; word-break: break-all;
                     margin-top: 20px; }
            </style>
            </head><body>
              <h1>Can't open this page</h1>
              <p>$safeDesc</p>
              <p class="url">$safeUrl</p>
            </body></html>
        """.trimIndent()
    }

    companion object {
        const val EXTRA_SHORTCUT_URL = "com.minimalbrowser.EXTRA_SHORTCUT_URL"
    }
}
