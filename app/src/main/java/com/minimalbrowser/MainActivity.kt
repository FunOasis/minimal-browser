package com.minimalbrowser

import android.annotation.SuppressLint
import android.os.Bundle
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

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
        }

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }

        binding.btnGo.setOnClickListener { navigate() }
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnReload.setOnClickListener { binding.webView.reload() }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) { navigate(); true } else false
        }

        // --- Hardware / gesture back → history back, else exit -----------
        // Precedence matches every other Android browser: back navigates,
        // only exits at the root of history.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    // Disable this callback so the framework handles exit
                    // normally (finish + system animation).
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // --- Restore after process death (background kill) ---------------
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
        // Preserves back/forward stack and scroll position across process
        // death, not just rotation. configChanges handles rotation.
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
    // BrowserUiListener — called from BlockingWebViewClient on the main
    // thread (all WebViewClient callbacks fire on main).
    // ---------------------------------------------------------------------

    override fun onUrlChanged(url: String) {
        // Don't stomp on the user while they're editing the address bar.
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
        // Clear stale title from the previous page. The new title will    
        // arrive via onReceivedTitle once the new page's <head> is parsed.    
        supportActionBar?.title = "Loading…"
    }

    override fun onPageLoadFinished() {
        binding.swipeRefresh.isRefreshing = false
    }

    override fun onPageLoadError(description: String, url: String?) {
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.webView.loadDataWithBaseURL(
            null,
            errorPageHtml(description, url),
            "text/html",
            "utf-8",
            null
        )
    }

    override fun onTitleChanged(title: String) {
    // Strip whitespace; some pages ship empty or padded <title> tags.    
        val clean = title.trim().ifEmpty { "MinimalBrowser" }    
        supportActionBar?.title = clean
    }

    override fun onFaviconReceived(icon: Bitmap?) {
    // ActionBar.setLogo places the bitmap in the toolbar's leading slot
    // (to the left of the title). Pass null to clear, which happens when
    // a page explicitly removes its favicon.    
        supportActionBar?.setLogo(icon)
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
            "https://duckduckgo.com/?q=" + URLEncoder.encode(input, "UTF-8")
        else -> "https://$input"
    }

    /** Minimal, self-contained error page. No external CSS/JS — just inline. */
    private fun errorPageHtml(description: String, url: String?): String {
        val safeDesc = description.replace("<", "&lt;").replace("&", "&amp;")
        val safeUrl  = (url ?: "").replace("<", "&lt;").replace("&", "&amp;")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; background: #ffffff; }
              body { font-family: -apple-system, system-ui, sans-serif;
                     color: #0d0d0d; padding: 64px 24px; text-align: center; }
              h1 { font-size: 20px; font-weight: 600; margin: 0 0 8px; }
              p  { font-size: 15px; color: #555; margin: 0 0 4px; }
              .url { font-size: 12px; color: #999; word-break: break-all;
                     margin-top: 20px; }
            </style>
            </head><body>
              <h1>Can't open this page</h1>
              <p>$safeDesc</p>
              <p class="url">$safeUrl</p>
            </body></html>
        """.trimIndent()
    }

    // ---------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browser_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
            else -> super.onOptionsItemSelected(item)
        }
    }
}
