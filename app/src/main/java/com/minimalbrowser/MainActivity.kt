package com.minimalbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.ArrayList

class MainActivity : AppCompatActivity(), BrowserUiListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var blocker: AdBlocker
    private lateinit var prefs: Prefs
    private lateinit var tabManager: TabManager
    private lateinit var history: HistoryStore

    private var lastSeenTabId: Long = -1L

    private var suggestionPopup: PopupWindow? = null
    private var suggestionList: ListView? = null
    private var suggestionAdapter: ArrayAdapter<String>? = null
    private var currentSuggestions: List<HistoryStore.Entry> = emptyList()
    private var suppressSuggestionRefresh = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        blocker = AdBlocker.get(this)
        prefs   = Prefs.get(this)
        history = HistoryStore.get(this)

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
            tabManager.canActiveScrollUp()
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
            if (isGo) { dismissSuggestions(); navigate(); true } else false
        }

        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.addressBar.post { binding.addressBar.selectAll() }
                // Suggestions only appear once the user types — an empty
                // focus does not dump history.
            } else {
                dismissSuggestions()
            }
        }

        binding.addressBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggestionRefresh) return
                if (binding.addressBar.hasFocus()) {
                    maybeShowSuggestions(s?.toString().orEmpty())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (suggestionPopup?.isShowing == true) {
                    dismissSuggestions()
                    return
                }
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

        // Tab set restore priority:
        //   1. If we have saved state, rebuild the tab set from saved URLs.
        //      A shortcut URL arriving on top of restored tabs loads in the
        //      active tab rather than creating a new one, so the restored
        //      layout is not disturbed.
        //   2. Else if a shortcut URL is present in the intent, start with
        //      one tab at that URL.
        //   3. Else start with a single home tab.
        val savedUrls = savedInstanceState?.getStringArrayList(KEY_TAB_URLS)
        val savedActive = savedInstanceState?.getInt(KEY_ACTIVE_TAB, 0) ?: 0
        val shortcutUrl = intent?.getStringExtra(EXTRA_SHORTCUT_URL)

        if (!savedUrls.isNullOrEmpty()) {
            tabManager.restore(savedUrls, savedActive)
            if (!shortcutUrl.isNullOrBlank()) {
                intent.removeExtra(EXTRA_SHORTCUT_URL)
                tabManager.getActiveWebView()?.loadUrl(shortcutUrl)
            }
        } else if (!shortcutUrl.isNullOrBlank()) {
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
                setAddressBarText(displayUrl(url))
            }
        }
    }

    /**
     * Persist enough tab state to rebuild the session if the OS kills
     * the process while we are backgrounded. We deliberately do not use
     * WebView.saveState() -- it is heavy and unreliable across WebView
     * versions. Saving URLs and the active index is enough: on restore,
     * only the active tab is loaded immediately; the rest stay dormant
     * until tapped.
     *
     * Note: rotation does not hit this path because the manifest lists
     * orientation|screenSize|keyboardHidden in configChanges. This only
     * fires on actual process death, which is exactly when we want it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tabs = tabManager.tabs
        if (tabs.isNotEmpty()) {
            val urls = ArrayList<String>(tabs.size)
            for (t in tabs) urls.add(t.url)
            outState.putStringArrayList(KEY_TAB_URLS, urls)
            outState.putInt(KEY_ACTIVE_TAB, tabManager.getActiveIndex())
        }
    }

    override fun onResume() {
        super.onResume()
        tabManager.getActiveWebView()?.resumeTimers()
        tabManager.resumeActive()
    }

    override fun onPause() {
        tabManager.pauseAll()
        tabManager.getActiveWebView()?.pauseTimers()
        super.onPause()
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
        dismissSuggestions()
        tabManager.destroyAll()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Suggestions
    // -------------------------------------------------------------------------

    private fun maybeShowSuggestions(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            dismissSuggestions()
            return
        }

        val entries = history.loadAll()
        val filtered = entries.asSequence()
            .filter { e ->
                e.url.lowercase().contains(q) ||
                    e.title.lowercase().contains(q)
            }
            .take(MAX_VISIBLE_SUGGESTIONS)
            .toList()

        if (filtered.isEmpty()) {
            dismissSuggestions()
            return
        }

        currentSuggestions = filtered

        val labels = filtered.map { e ->
            if (e.title.isBlank()) e.url else e.title + "\n" + e.url
        }

        if (suggestionPopup == null) {
            buildSuggestionsPopup()
        }
        suggestionAdapter?.clear()
        suggestionAdapter?.addAll(labels)
        suggestionAdapter?.notifyDataSetChanged()

        val popup = suggestionPopup ?: return
        if (popup.isShowing) {
            popup.update(
                binding.urlPill,
                binding.urlPill.width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        } else {
            popup.width = binding.urlPill.width
            popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
            popup.showAsDropDown(binding.urlPill, 0, 4)
        }
    }

    private fun buildSuggestionsPopup() {
        val listView = ListView(this).apply {
            divider = ColorDrawable(Color.parseColor("#1AFFFFFF"))
            dividerHeight = 1
            setBackgroundColor(Color.parseColor("#E60D0D0D"))
            setPadding(0, 4, 0, 4)
        }

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            ArrayList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(Color.parseColor("#F5F5F5"))
                tv.textSize = 14f
                tv.setPadding(32, 24, 32, 24)
                tv.maxLines = 2
                tv.ellipsize = TextUtils.TruncateAt.END
                return v
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = currentSuggestions.getOrNull(position) ?: return@setOnItemClickListener
            suppressSuggestionRefresh = true
            binding.addressBar.setText(displayUrl(entry.url))
            suppressSuggestionRefresh = false
            binding.addressBar.setSelection(binding.addressBar.text?.length ?: 0)
            binding.addressBar.clearFocus()
            dismissSuggestions()
            val wv = tabManager.getActiveWebView() ?: return@setOnItemClickListener
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
            wv.loadUrl(entry.url)
        }

        val popup = PopupWindow(
            listView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            elevation = 12f
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#E60D0D0D")))
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }

        suggestionPopup = popup
        suggestionList = listView
        suggestionAdapter = adapter
    }

    private fun dismissSuggestions() {
        suggestionPopup?.dismiss()
    }

    private fun setAddressBarText(text: String) {
        suppressSuggestionRefresh = true
        binding.addressBar.setText(text)
        suppressSuggestionRefresh = false
    }

    // -------------------------------------------------------------------------
    // Menu / tabs / dialogs
    // -------------------------------------------------------------------------

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

    private fun showTabSwitcher() {
        TabSwitcherSheet(
            context = this,
            tabManager = tabManager,
            onVisibilityChanged = { visible ->
                if (visible) {
                    binding.footerText.visibility = View.VISIBLE
                } else {
                    val url = tabManager.getActiveWebView()?.url ?: ""
                    updateFooterFor(url)
                }
            },
            onLastTabCloseRequested = {
                showExitConfirm()
            }
        ).show()
    }

    private fun updateTabBadge() {
        val active = tabManager.getActiveIndex() + 1
        val total = tabManager.count()
        binding.tabBadge.text = active.toString() + "/" + total
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

    // -------------------------------------------------------------------------
    // Ad blocking dialog — warehouse driven
    // -------------------------------------------------------------------------

    private fun showCustomFiltersDialog() {
        val store = BlocklistStore.get(this)
        val view = layoutInflater.inflate(R.layout.dialog_custom_filters, null)

        val editSubs            = view.findViewById<EditText>(R.id.editSubscriptions)
        val warehouseContainer  = view.findViewById<LinearLayout>(R.id.warehouseContainer)
        val warehouseEmptyText  = view.findViewById<TextView>(R.id.warehouseEmptyText)
        val editBlocklist       = view.findViewById<EditText>(R.id.editBlocklist)
        val editPatterns        = view.findViewById<EditText>(R.id.editPatterns)
        val statsView           = view.findViewById<TextView>(R.id.customFilterStats)

        editBlocklist.setText(prefs.customBlocklist)
        editPatterns.setText(prefs.customFilters)

        fun refreshStats() {
            val s = blocker.stats()
            statsView.text = getString(R.string.custom_filters_stats, s.hosts, s.patterns)
        }

        fun refreshWarehouse() {
            val entries = store.listAll()
            warehouseContainer.removeAllViews()
            if (entries.isEmpty()) {
                warehouseEmptyText.visibility = View.VISIBLE
            } else {
                warehouseEmptyText.visibility = View.GONE
                for (e in entries) {
                    val row = layoutInflater.inflate(
                        R.layout.item_warehouse_list,
                        warehouseContainer,
                        false
                    )
                    val urlView = row.findViewById<TextView>(R.id.warehouseUrl)
                    val metaView = row.findViewById<TextView>(R.id.warehouseMeta)
                    val removeBtn = row.findViewById<ImageButton>(R.id.removeWarehouseList)

                    urlView.text = e.url

                    val hostLabel = e.hostCount.toString() + " hosts"
                    val sizeLabel = humanSize(e.byteSize)
                    val ageLabel = humanAge(e.lastFetched)
                    val prefix = if (e.ok) "" else "FAILED - "
                    metaView.text = prefix + hostLabel + " - " + sizeLabel + " - " + ageLabel

                    removeBtn.setOnClickListener {
                        store.remove(e.url)
                        blocker.reloadCustomRules()
                        refreshWarehouse()
                        refreshStats()
                    }

                    warehouseContainer.addView(row)
                }
            }
            refreshStats()
        }

        refreshWarehouse()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_custom_filters)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val rawSubs = editSubs.text.toString()
                val newUrls = rawSubs.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                    .toList()

                prefs.customBlocklist = editBlocklist.text.toString()
                prefs.customFilters = editPatterns.text.toString()

                val existing = store.listAll().map { it.url }.toSet()
                val toAdd = newUrls.filter { it !in existing }

                if (toAdd.isEmpty()) {
                    blocker.reloadCustomRules()
                    Toast.makeText(
                        this,
                        R.string.custom_filters_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Fetching " + toAdd.size + " new list(s)...",
                        Toast.LENGTH_SHORT
                    ).show()
                    fetchNewLists(store, toAdd)
                }
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                prefs.customBlocklist = ""
                prefs.customFilters = ""
                blocker.reloadCustomRules()
                Toast.makeText(this, R.string.custom_filters_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Fetch newly added lists on the activity's lifecycle scope, not a
     * bare CoroutineScope. If the user rotates or leaves the dialog
     * mid-fetch, the coroutine is cancelled with the activity and the
     * withContext(Main) block never runs against a destroyed context.
     */
    private fun fetchNewLists(store: BlocklistStore, urls: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            var failed = 0
            for (u in urls) {
                val result = store.addAndFetch(u)
                if (result.ok) ok++ else failed++
            }
            withContext(Dispatchers.Main) {
                blocker.reloadCustomRules()
                val msg = if (failed == 0) {
                    "Added " + ok + " list(s)"
                } else {
                    "Added " + ok + ", " + failed + " failed"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun humanSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + " MB"
            bytes >= 1024L         -> (bytes / 1024L).toString() + " KB"
            else                    -> bytes.toString() + " B"
        }
    }

    private fun humanAge(ms: Long): String {
        if (ms <= 0L) return "never"
        val diff = System.currentTimeMillis() - ms
        if (diff < 0L) return "just now"
        val mins = diff / 60_000L
        if (mins < 1L) return "just now"
        if (mins < 60L) return mins.toString() + "m ago"
        val hours = mins / 60L
        if (hours < 24L) return hours.toString() + "h ago"
        val days = hours / 24L
        return days.toString() + "d ago"
    }

    // -------------------------------------------------------------------------
    // BrowserUiListener
    // -------------------------------------------------------------------------

    override fun onUrlChanged(view: WebView, url: String) {
        if (view !== tabManager.getActiveWebView()) return
        updateFooterFor(url)

        // Internal home has no favicon; wipe stale state on arrival.
        if (url.startsWith(Prefs.HOME_URL)) {
            tabManager.getActive()?.favicon = null
            binding.favicon.setImageDrawable(null)
            binding.favicon.visibility = View.GONE
        }

        val currentTabId = tabManager.getActive()?.id ?: -1L
        val tabChanged = currentTabId != lastSeenTabId
        lastSeenTabId = currentTabId

        if (binding.addressBar.hasFocus() && !tabChanged) return
        setAddressBarText(displayUrl(url))
    }

    override fun onNavStateChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean) {
        if (view !== tabManager.getActiveWebView()) return
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
        val url = view.url ?: return
        val title = view.title.orEmpty()
        history.record(url, title)
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

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    private fun loadHome() {
        val wv = tabManager.getActiveWebView() ?: return
        setAddressBarText("")
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
        else -> "https://" + input
    }

    private fun errorPageHtml(description: String, url: String?): String {
        val safeDesc = description.replace("<", "&lt;").replace("&", "&amp;")
        val safeUrl  = (url ?: "").replace("<", "&lt;").replace("&", "&amp;")
        return "<!DOCTYPE html>" +
            "<html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>" +
            "html, body { margin: 0; padding: 0; background: #0A0A0A; }" +
            "body { font-family: -apple-system, system-ui, sans-serif; " +
            "color: #FFFFFF; padding: 64px 24px; text-align: center; }" +
            "h1 { font-size: 20px; font-weight: 600; margin: 0 0 8px; }" +
            "p  { font-size: 15px; color: #8A8A8A; margin: 0 0 4px; }" +
            ".url { font-size: 12px; color: #666666; word-break: break-all; " +
            "margin-top: 20px; }" +
            "</style>" +
            "</head><body>" +
            "<h1>Can't open this page</h1>" +
            "<p>" + safeDesc + "</p>" +
            "<p class=\"url\">" + safeUrl + "</p>" +
            "</body></html>"
    }

    companion object {
        const val EXTRA_SHORTCUT_URL = "com.minimalbrowser.EXTRA_SHORTCUT_URL"
        private const val MAX_VISIBLE_SUGGESTIONS = 6
        private const val KEY_TAB_URLS = "mb_tab_urls"
        private const val KEY_ACTIVE_TAB = "mb_active_tab"
    }
}
