package com.minimalbrowser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.minimalbrowser.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.util.ArrayList

class MainActivity : AppCompatActivity(), BrowserUiListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var blocker: AdBlocker
    private lateinit var prefs: Prefs
    private lateinit var tabManager: TabManager
    private lateinit var history: HistoryStore

    private var lastSeenTabId: Long = -1L

    private var exiting: Boolean = false
    private var isBackgrounded: Boolean = false

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var suggestionPopup: PopupWindow? = null
    private var suggestionList: ListView? = null
    private var suggestionAdapter: ArrayAdapter<String>? = null
    private var currentSuggestions: List<HistoryStore.Entry> = emptyList()
    private var suppressSuggestionRefresh = false

    private val freezeHandler = Handler(Looper.getMainLooper())
    private val freezeTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val frozen = tabManager.freezeIdleTabs(isBackgrounded)
            if (frozen > 0) {
                updateTabBadge()
            }
            freezeHandler.postDelayed(this, FREEZE_CHECK_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf("android.permission.POST_NOTIFICATIONS"), 9001
                )
            }
        }

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
            onTabsChanged = { updateTabBadge() },
            onFullscreenShow = { view, callback -> enterFullscreen(view, callback) },
            onFullscreenHide = { exitFullscreen() },
            onLongPressLink = { url -> showLinkOptions(url) }
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
        binding.jsBadge.setOnClickListener { toggleActiveJavaScript() }
        binding.blockCountBadge.setOnClickListener { showBlockedCountDetail() }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) { dismissSuggestions(); navigate(); true } else false
        }

        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.addressBar.post { binding.addressBar.selectAll() }
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
                if (tabManager.isInFullscreen()) {
                    requestExitFullscreen()
                    return
                }
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

        val savedUrls = savedInstanceState?.getStringArrayList(KEY_TAB_URLS)
        val savedActive = savedInstanceState?.getInt(KEY_ACTIVE_TAB, 0) ?: 0
        val shortcutUrl = intent?.getStringExtra(EXTRA_SHORTCUT_URL)

        val disk = if (savedUrls.isNullOrEmpty()) SessionStore.load(this) else null

        val restoreUrls: List<String>? = when {
            !savedUrls.isNullOrEmpty() -> savedUrls
            disk != null -> disk.urls
            else -> null
        }
        val restoreActive: Int = when {
            !savedUrls.isNullOrEmpty() -> savedActive
            disk != null -> disk.activeIndex
            else -> 0
        }

        if (restoreUrls != null) {
            tabManager.restore(restoreUrls, restoreActive)
            if (!shortcutUrl.isNullOrBlank()) {
                intent.removeExtra(EXTRA_SHORTCUT_URL)
                tabManager.loadActive(shortcutUrl)
            }
        } else if (!shortcutUrl.isNullOrBlank()) {
            intent.removeExtra(EXTRA_SHORTCUT_URL)
            tabManager.create(url = shortcutUrl)
        } else {
            tabManager.create()
        }

        updateJsBadge()
        updateBlockCountBadge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_SHORTCUT_URL)
        intent.removeExtra(EXTRA_SHORTCUT_URL)
        if (!url.isNullOrBlank()) {
            tabManager.loadActive(url)
            setAddressBarText(displayUrl(url))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (exiting || isFinishing) return
        val tabs = tabManager.tabs
        if (tabs.isEmpty()) return
        val urls = ArrayList<String>(tabs.size)
        for (t in tabs) urls.add(t.url)
        outState.putStringArrayList(KEY_TAB_URLS, urls)
        outState.putInt(KEY_ACTIVE_TAB, tabManager.getActiveIndex())
    }

    override fun onStop() {
        super.onStop()
        if (exiting || isFinishing) return
        val tabs = tabManager.tabs
        if (tabs.isEmpty()) return
        val urls = ArrayList<String>(tabs.size)
        val titles = ArrayList<String>(tabs.size)
        for (t in tabs) {
            urls.add(t.url)
            titles.add(t.title)
        }
        SessionStore.save(this, urls, titles, tabManager.getActiveIndex())
    }

    override fun onResume() {
        super.onResume()
        isBackgrounded = false

        if (fullscreenView != null) hideSystemBars()

        tabManager.unfreezeActiveIfFrozen()

        tabManager.getActiveWebView()?.resumeTimers()
        tabManager.resumeActive()

        freezeHandler.removeCallbacks(freezeTick)
        freezeHandler.postDelayed(freezeTick, FREEZE_CHECK_INTERVAL_MS)

        updateJsBadge()
        updateBlockCountBadge()
    }

    override fun onPause() {
        isBackgrounded = true

        val frozen = tabManager.freezeIdleTabs(isBackgrounded = true)
        if (frozen > 0) updateTabBadge()

        freezeHandler.removeCallbacks(freezeTick)

        tabManager.pauseAll()
        tabManager.getActiveWebView()?.pauseTimers()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            tabManager.trimAllCaches()
            tabManager.freezeIdleTabs(isBackgrounded = false)
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
        fullscreenView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        fullscreenView = null
        fullscreenCallback = null
        showSystemBars()

        dismissSuggestions()
        freezeHandler.removeCallbacks(freezeTick)
        tabManager.destroyAll()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Fullscreen
    // -------------------------------------------------------------------------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            try { callback.onCustomViewHidden() } catch (_: Throwable) {}
            return
        }
        fullscreenView = view
        fullscreenCallback = callback

        binding.topBar.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.footerText.visibility = View.GONE
        binding.swipeRefresh.visibility = View.GONE
        binding.webViewContainer.visibility = View.GONE

        val root = findViewById<ViewGroup>(android.R.id.content)
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hideSystemBars()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        fullscreenCallback = null

        (view.parent as? ViewGroup)?.removeView(view)

        binding.topBar.visibility = View.VISIBLE
        binding.footerText.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.VISIBLE

        showSystemBars()
    }

    private fun requestExitFullscreen() {
        val cb = fullscreenCallback ?: return
        try {
            cb.onCustomViewHidden()
        } catch (_: Throwable) {
            exitFullscreen()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // -------------------------------------------------------------------------
    // Long-press link
    // -------------------------------------------------------------------------

    private fun showLinkOptions(url: String) {
        val title = if (url.length > 72) url.substring(0, 72) + "..." else url
        val options = arrayOf(
            getString(R.string.link_option_copy),
            getString(R.string.link_option_new_tab),
            getString(R.string.link_option_background_tab)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyLinkToClipboard(url)
                    1 -> tabManager.create(url = url, makeActive = true)
                    2 -> tabManager.create(url = url, makeActive = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyLinkToClipboard(url: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show()
        }
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
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
            tabManager.loadActive(entry.url)
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

        val host = currentHost()

        val blockItem = popup.menu.findItem(R.id.action_toggle_site_blocking)
        if (blockItem != null) {
            if (host == null) {
                blockItem.isEnabled = false
                blockItem.title = getString(R.string.menu_disable_site_blocking)
            } else if (prefs.isHostDisabled(host)) {
                blockItem.title = getString(R.string.menu_enable_site_blocking)
            } else {
                blockItem.title = getString(R.string.menu_disable_site_blocking)
            }
        }

        val desktopItem = popup.menu.findItem(R.id.action_toggle_desktop)
        if (desktopItem != null) {
            if (host == null) {
                desktopItem.isEnabled = false
                desktopItem.title = getString(R.string.menu_desktop_site)
            } else if (prefs.isDesktopHost(host)) {
                desktopItem.title = getString(R.string.menu_mobile_site)
            } else {
                desktopItem.title = getString(R.string.menu_desktop_site)
            }
        }

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
            toggleActiveJavaScript()
            true
        }

        R.id.action_custom_filters -> {
            showCustomFiltersDialog()
            true
        }

        R.id.action_toggle_site_blocking -> {
            toggleSiteBlocking()
            true
        }

        R.id.action_toggle_desktop -> {
            toggleDesktopMode()
            true
        }

        R.id.action_downloads -> {
            startActivity(Intent(this, DownloadsActivity::class.java))
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
            exiting = true
            SessionStore.clear(this)
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
        updateJsBadge()
        updateBlockCountBadge()
    }

    private fun updateJsBadge() {
        binding.jsBadge.isSelected = tabManager.isActiveJsEnabled()
    }

    private fun toggleActiveJavaScript() {
        val enabled = tabManager.toggleActiveJavaScript()
        updateJsBadge()
        Toast.makeText(
            this,
            "JavaScript: " + (if (enabled) "ON" else "OFF"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateBlockCountBadge() {
        val count = tabManager.getActiveBlockedCount()
        if (count <= 0) {
            binding.blockCountBadge.visibility = View.GONE
        } else {
            binding.blockCountBadge.visibility = View.VISIBLE
            binding.blockCountBadge.text = count.toString()
        }
    }

    private fun showBlockedCountDetail() {
        val count = tabManager.getActiveBlockedCount()
        Toast.makeText(
            this,
            getString(R.string.blocked_count_toast, count),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun currentHost(): String? {
        val url = tabManager.getActiveWebView()?.url ?: return null
        if (url.startsWith("minimal://")) return null
        return try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }

    private fun toggleSiteBlocking() {
        val host = currentHost() ?: return
        val wv = tabManager.getActiveWebView() ?: return
        if (prefs.isHostDisabled(host)) {
            prefs.removeDisabledHostsMatching(host)
            Toast.makeText(
                this,
                getString(R.string.blocking_enabled_toast, host),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            prefs.addDisabledHost(host)
            Toast.makeText(
                this,
                getString(R.string.blocking_disabled_toast, host),
                Toast.LENGTH_SHORT
            ).show()
        }
        blocker.invalidateCache()
        wv.reload()
    }

    private fun toggleDesktopMode() {
        val host = currentHost() ?: return
        val wv = tabManager.getActiveWebView() ?: return
        val nowDesktop: Boolean
        if (prefs.isDesktopHost(host)) {
            prefs.removeDesktopHostMatching(host)
            nowDesktop = false
            Toast.makeText(
                this,
                getString(R.string.desktop_off_toast, host),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            prefs.addDesktopHost(host)
            nowDesktop = true
            Toast.makeText(
                this,
                getString(R.string.desktop_on_toast, host),
                Toast.LENGTH_SHORT
            ).show()
        }
        WebViewConfigurator.applyUserAgent(this, wv, nowDesktop)
        wv.reload()
    }

    private fun updateFooterFor(url: String) {
        binding.footerText.visibility =
            if (url.startsWith(Prefs.HOME_URL)) View.VISIBLE else View.GONE
    }

    private fun showExitConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ ->
                exiting = true
                SessionStore.clear(this)
                finishAndRemoveTask()
            }
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
    // Ad blocking dialog
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
            statsView.text = getString(
                R.string.custom_filters_stats,
                s.hosts,
                s.patterns,
                CosmeticFilter.ruleCount()
            )
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
        updateBlockCountBadge()
    }

    override fun onNavStateChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean) {
        if (view !== tabManager.getActiveWebView()) return
        binding.btnForward.isEnabled = canGoForward
    }

    override fun onPageLoadStarted(view: WebView) {
        if (view !== tabManager.getActiveWebView()) return
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        updateBlockCountBadge()
    }

    override fun onPageLoadFinished(view: WebView) {
        if (view !== tabManager.getActiveWebView()) return
        binding.swipeRefresh.isRefreshing = false
        val url = view.url ?: return
        val title = view.title.orEmpty()
        history.record(url, title)
        updateBlockCountBadge()
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
            webView = view,
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
        setAddressBarText("")
        tabManager.loadActive(Prefs.HOME_URL)
    }

    private fun displayUrl(url: String): String =
        if (url.startsWith("minimal://")) "" else url

    private fun navigate() {
        val input = binding.addressBar.text.toString().trim()
        if (input.isEmpty()) return
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        tabManager.loadActive(normalize(input))
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

        private const val FREEZE_CHECK_INTERVAL_MS = 60_000L
    }
}
