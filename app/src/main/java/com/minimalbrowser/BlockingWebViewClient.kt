package com.minimalbrowser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI

interface BrowserUiListener {
    fun onUrlChanged(view: WebView, url: String)
    fun onNavStateChanged(view: WebView, canGoBack: Boolean, canGoForward: Boolean)
    fun onPageLoadStarted(view: WebView)
    fun onPageLoadFinished(view: WebView)
    fun onPageLoadError(view: WebView?, description: String, url: String?)
    fun onRenderProcessGone(view: WebView)
    fun onDownloadRequested(view: WebView, url: String)
}

class BlockingWebViewClient(
    private val blocker: AdBlocker,
    private val appContext: Context,
    private val ui: BrowserUiListener
) : WebViewClient() {

    companion object {
        private const val TAG = "BlockingWebViewClient"
        private val EMPTY_BODY = ByteArray(0)

        private const val INTERNAL_SCHEME = "minimal"
        private const val HOME_URL = "minimal://home"

        private val EXTERNAL_SCHEMES = setOf(
            "mailto", "tel", "sms", "smsto", "mms", "mmsto",
            "geo", "market", "intent"
        )

        private val DOWNLOAD_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst",
            "apk", "aab", "exe", "msi", "dmg", "deb", "rpm", "jar",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf",
            "md", "markdown",
            "json", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini",
            "js", "mjs", "cjs", "ts", "jsx", "tsx",
            "css", "scss", "sass", "less",
            "py", "rb", "go", "rs", "kt", "kts", "java", "scala", "swift",
            "c", "cc", "cpp", "h", "hpp", "cs", "php",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "sql", "graphql", "proto",
            "torrent", "iso", "img", "epub", "mobi", "azw3"
        )

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content", emptyMap(),
            ByteArrayInputStream(EMPTY_BODY)
        )
    }

    /**
     * Count of subresource requests blocked on the current top-level
     * navigation. Reset in onPageStarted for each main-frame load.
     */
    @Volatile
    var blockedCount: Int = 0
        private set

    private val prefs: Prefs by lazy { Prefs.get(appContext) }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (request.isForMainFrame && url.startsWith(HOME_URL)) return serveHomePage()
        if (request.isForMainFrame) return null
        if (blocker.isBlocked(url)) {
            blockedCount = blockedCount + 1
            return blockedResponse()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase() ?: return true
        val isMainFrame = request.isForMainFrame

        return when (scheme) {
            "http", "https" -> {
                if (isDownloadUrl(uri)) {
                    view?.let { ui.onDownloadRequested(it, uri.toString()) }
                    true
                } else if (isMainFrame && view != null) {
                    // Apply per-host UA and strip tracking params before
                    // the navigation proceeds. If either changed, we
                    // must load the target ourselves -- returning false
                    // would let WebView follow the original URL with
                    // the old settings.
                    val host = uri.host?.lowercase()?.removePrefix("www.")
                    if (host != null) {
                        val desktop = prefs.isDesktopHost(host)
                        WebViewConfigurator.applyUserAgent(appContext, view, desktop)
                    }
                    val cleaned = UrlCleaner.clean(uri.toString(), prefs.removeParams)
                    if (cleaned != uri.toString()) {
                        view.loadUrl(cleaned)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            "about", INTERNAL_SCHEME -> false
            in EXTERNAL_SCHEMES -> { dispatchExternal(uri); true }
            else -> {
                Log.w(TAG, "Refusing navigation with scheme '" + scheme + "'")
                true
            }
        }
    }

    private fun isDownloadUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase()
        if (host == "github.com" || host == "www.github.com") {
            val path = uri.path.orEmpty()
            if (path.contains("/blob/") || path.contains("/tree/")) return false
        }
        if (host == "gitlab.com" || host == "www.gitlab.com") {
            val path = uri.path.orEmpty()
            if (path.contains("/-/blob/") || path.contains("/-/tree/")) return false
        }
        if (host == "bitbucket.org" || host == "www.bitbucket.org") {
            val path = uri.path.orEmpty()
            if (path.contains("/src/") || path.contains("/browse/")) return false
        }

        val last = uri.lastPathSegment ?: return false
        val dot = last.lastIndexOf('.')
        if (dot <= 0 || dot == last.length - 1) return false
        val ext = last.substring(dot + 1).lowercase()
        if (ext.isEmpty() || ext.length > 8) return false
        if (!ext.all { it.isLetterOrDigit() }) return false
        return ext in DOWNLOAD_EXTENSIONS
    }

    private fun dispatchExternal(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app handles " + uri.scheme + ": " + uri)
        }
    }

    private fun serveHomePage(): WebResourceResponse {
        return try {
            val html = appContext.assets
                .open("home.html")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-cache"),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.w(TAG, "home.html unreadable: " + e.message)
            val fallback = "<html><body style='background:#000;color:#fff;" +
                "font-family:sans-serif;padding:32px'>" +
                "<h2>Home page unavailable</h2></body></html>"
            WebResourceResponse(
                "text/html", "utf-8", 500, "Internal Error", emptyMap(),
                ByteArrayInputStream(fallback.toByteArray(Charsets.UTF_8))
            )
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view == null) return
        blockedCount = 0
        BlobDownloadHelper.injectCapture(view, url)
        ui.onPageLoadStarted(view)
        if (url != null) ui.onUrlChanged(view, url)
        ui.onNavStateChanged(view, view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view == null) return
        BlobDownloadHelper.injectCapture(view, url)
        if (!isHostDisabled(url)) {
            CosmeticFilter.apply(view, url)
        }
        PageScrollProbe.install(view, url)
        ui.onPageLoadFinished(view)
        if (url != null) ui.onUrlChanged(view, url)
        ui.onNavStateChanged(view, view.canGoBack(), view.canGoForward())
    }

    private fun isHostDisabled(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null }
            ?: return false
        return prefs.isHostDisabled(host)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view == null) return
        if (url != null) ui.onUrlChanged(view, url)
        ui.onNavStateChanged(view, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val desc = error?.description?.toString() ?: "Page failed to load"
            ui.onPageLoadError(view, desc, request.url?.toString())
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler,
        error: SslError?
    ) {
        Log.w(TAG, "SSL error, refusing to proceed: " + error?.primaryError)
        handler.cancel()
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        Log.w(TAG, "Render process gone. Crashed=" + detail?.didCrash())
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            ui.onRenderProcessGone(view)
        }
        return true
    }
}
