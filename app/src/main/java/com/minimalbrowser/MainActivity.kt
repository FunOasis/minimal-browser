package com.minimalbrowser

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
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
            onFullscreenHide = { exitFullscreen() }
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
                tabManager.getActiveWebView()?.loadUrl(shortcutUrl)
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
            val active = tabManager.getActiveWebView()
            if (active != null) {
                active.loadUrl(url)
                setAddressBarText(displayUrl(url))
            }
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
        binding.webViewContainer.visibility =
