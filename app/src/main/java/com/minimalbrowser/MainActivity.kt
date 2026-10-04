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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        blocker = AdBlocker.get(this)
        prefs   = Prefs.get(this)

        WebViewConfigurator.apply(binding.webView, prefs.javaScriptEnabled)

        binding.webView.webViewClient = BlockingWebViewClient(
            blocker = blocker,
            appContext = applicationContext,
            ui = this
        )

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress == 100) binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                if (icon == null) {
                    binding.favicon.setImageDrawable(null)
                    binding.favicon.visibility = View.GONE
                } else {
                    binding.favicon.setImageBitmap(icon)
                    binding.favicon.visibility = View.VISIBLE
                }
            }
        }

        // Hand any file download to the system DownloadManager. Fires when
        // WebView can't render a response in-page — Content-Disposition
        // attachments, <a download> links, and unhandled MIME types.
        binding.webView.setDownloadListener {
                url, userAgent, contentDisposition, mimeType, contentLength ->
            DownloadHandler.handle(
                activity = this,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength
            )
        }

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }

        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnMenu.setOnClickListener { showOverflowMenu(it) }

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
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadHome()
        }

        refreshNavButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            binding.webView.clearCache(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Give DownloadHandler a chance to pick up any deferred download
        // that was waiting on WRITE_EXTERNAL_STORAGE (API 26-28 only).
        DownloadHandler.onPermissionResult(this, requestCode, grantResults)
    }

    override fun onDestroy() {
        with(binding.webView) {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Overflow menu
    // ---------------------------------------------------------------------

    private fun showOverflowMenu(anchor: View) {
        val themedContext = ContextThemeWrapper(
            this,
            R.style.ThemeOverlay_MinimalBrowser_PopupMenu
        )
        val popup = PopupMenu(themedContext, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.setOnMenuItemClickListener { item -> handleMenu(item) }
        popup.show()
    }

    private fun handleMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_home -> { loadHome(); true }

        R.id.action_js -> {
            prefs.javaScriptEnabled = !prefs.javaScriptEnabled
            binding.webView.settings.javaScriptEnabled = prefs.javaScriptEnabled
            binding.webView.reload()
            Toast.makeText(
                this,
                "JavaScript: ${if (prefs.javaScriptEnabled) "ON" else "OFF"}",
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        R.id.action_custom_filters -> {
            showCustomFiltersDialog()
            true
        }

        R.id.action_downloads -> {
            // Hand off to the system Downloads UI (the one built into
            // every Android build). We don't ship our own viewer — that
            // would double the app's surface area for no real gain.
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_SHORT).show()
            }
            true
        }

        R.id.action_clear -> {
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
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
    // Custom filters dialog
    // ---------------------------------------------------------------------

    /**
     * Opens the Custom filters dialog.
     *
     * Two text areas — one for hosts, one for URL keywords. On Save we
     * persist to Prefs and call blocker.reloadCustomRules(), which
     * merges the new rules on a background thread and hot-swaps the
     * matcher. Clear wipes both fields and reverts to just the built-in
     * assets. Cancel leaves prefs untouched.
     *
     * The stats line shows the *currently active* rule counts so the
     * user can verify the merge landed (reopen the dialog after Save to
     * see the updated numbers).
     */
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
    // BrowserUiListener
    // ---------------------------------------------------------------------

    override fun onUrlChanged(url: String) {
        if (binding.addressBar.hasFocus()) return
        binding.addressBar.setText(displayUrl(url))
    }

    override fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        binding.btnBack.isEnabled    = canGoBack
        binding.btnForward.isEnabled = canGoForward
    }

    override fun onPageLoadStarted() {
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onPageLoadFinished() {
        binding.swipeRefresh.isRefreshing = false
    }

    override fun onPageLoadError(description: String, url: String?) {
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.favicon.setImageDrawable(null)
        binding.favicon.visibility = View.GONE
        binding.webView.loadDataWithBaseURL(
            null, errorPageHtml(description, url), "text/html", "utf-8", null
        )
    }

    override fun onRenderProcessGone() {
        Toast.makeText(this, "Renderer crashed — restarting", Toast.LENGTH_SHORT).show()
        recreate()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun refreshNavButtons() {
        binding.btnBack.isEnabled    = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
    }

    private fun loadHome() {
        binding.addressBar.setText("")
        binding.webView.loadUrl(Prefs.HOME_URL)
    }

    private fun displayUrl(url: String): String =
        if (url.startsWith("minimal://")) "" else url

    private fun navigate() {
        val input = binding.addressBar.text.toString().trim()
        if (input.isEmpty()) return
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.webView.loadUrl(normalize(input))
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
}
