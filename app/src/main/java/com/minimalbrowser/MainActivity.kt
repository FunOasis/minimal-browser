package com.minimalbrowser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.minimalbrowser.databinding.ActivityMainBinding
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

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
        supportActionBar?.setDisplayShowTitleEnabled(false)

        with(binding.webView.settings) {
            javaScriptEnabled    = prefs.javaScriptEnabled
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            builtInZoomControls  = true
            displayZoomControls  = false
        }

        binding.webView.webViewClient = BlockingWebViewClient(blocker)

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
        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnReload.setOnClickListener { binding.webView.reload() }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) { navigate(); true } else false
        }

        if (savedInstanceState == null) {
            val home = prefs.homepage
            binding.webView.loadUrl(home)
            binding.addressBar.setText(home)
        }
    }

    private fun navigate() {
        val input = binding.addressBar.text.toString().trim()
        if (input.isEmpty()) return
        binding.webView.loadUrl(normalize(input))
        binding.addressBar.clearFocus()
    }

    private fun normalize(input: String): String = when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains(" ") || !input.contains(".") ->
            "https://duckduckgo.com/?q=" + URLEncoder.encode(input, "UTF-8")
        else -> "https://$input"
    }

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
