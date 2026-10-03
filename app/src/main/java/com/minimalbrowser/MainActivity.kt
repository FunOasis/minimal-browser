package com.minimalbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
                // Favicon sits inside the URL pill, leftmost. No background.
                if (icon == null) {
                    binding.favicon.setImageDrawable(null)
                    binding.favicon.visibility = View.GONE
                } else {
                    binding.favicon.setImageBitmap(icon)
                    binding.favicon.visibility = View.VISIBLE
                }
            }
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
            val home = prefs.homepage
            binding.webView.loadUrl(home)
            binding.addressBar.setText(home)
        }

        refreshNavButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
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
    // Overflow menu (was: ActionBar options menu)
    // ---------------------------------------------------------------------

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.setOnMenuItemClickListener { item -> handleMenu(item) }
        popup.show()
    }

    private fun handleMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_home -> {
            val home = prefs.homepage
            binding.webView.loadUrl(home)
            binding.addressBar.setText(home)
            true
        }
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
        R.id.action_clear -> {
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
            true
        }
        else -> false
    }

    // ---------------------------------------------------------------------
    // BrowserUiListener
    // ---------------------------------------------------------------------

    override fun onUrlChanged(url: String) {
        if (binding.addressBar.hasFocus()) return
        binding.addressBar.setText(url)
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

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun refreshNavButtons() {
        binding.btnBack.isEnabled    = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
    }

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
