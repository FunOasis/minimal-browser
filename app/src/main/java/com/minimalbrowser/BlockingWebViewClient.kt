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
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicInteger

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

        // The extra Intent.parseUri decodes the "S.browser_fallback_url=..."
        // parameter into. Chrome defined the contract; sites rely on it to
        // say "if the target app is missing, open THIS web page instead".
        private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"

        private val EXTERNAL_SCHEMES = setOf(
            "mailto", "tel", "sms", "smsto", "mms", "mmsto",
            "geo", "market", "intent",
            "magnet", "bitcoin", "tg", "whatsapp", "itms-apps", "vnd.youtube"
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

        // -----------------------------------------------------------------
        // Home page asset cache
        //
        // BlockingWebViewClient is instantiated once per tab. The asset
        // read (open + bufferedReader + readText) was previously paid
        // once per new tab, every time. One read per process is enough.
        // -----------------------------------------------------------------
        @Volatile private var cachedHomeHtml: String? = null

        private fun loadHomeHtml(ctx: Context): String {
            cachedHomeHtml?.let { return it }
            return synchronized(this) {
                cachedHomeHtml ?: run {
                    val text = try {
                        ctx.assets.open("home.html")
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    } catch (e: Exception) {
                        Log.w(TAG, "home.html unreadable: " + e.message)
                        "<html><body style='background:#000;color:#fff;" +
                            "font-family:sans-serif;padding:32px'>" +
                            "<h2>Home page unavailable</h2></body></html>"
                    }
                    cachedHomeHtml = text
                    text
                }
            }
        }
    }

    private val blockedCounter = AtomicInteger(0)

    val blockedCount: Int
        get() = blockedCounter.get()

    private val prefs: Prefs by lazy { Prefs.get(appContext) }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (request.isForMainFrame && url.startsWith(HOME_URL)) return serveHomePage()
        if (request.isForMainFrame) return null
        if (blocker.isBlocked(url)) {
            blockedCounter.incrementAndGet()
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
                    val host = uri.host?.lowercase()?.removePrefix("www.")
                    if (host != null) {
                        val desktop = prefs.isDesktopHost(host)
                        WebViewConfigurator.applyUserAgent(appContext, view, desktop)
                    }
                    val cleaned = UrlCleaner.clean(uri.toString(), prefs.removeParams)
                    if (cleaned != uri.toString()) {
                        view.post {
                            try { view.loadUrl(cleaned) } catch (_: Throwable) {}
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            "about", INTERNAL_SCHEME -> false
            in EXTERNAL_SCHEMES -> { dispatchExternal(view, uri); true }
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

    // -----------------------------------------------------------------
    // External scheme dispatch (mailto:, tel:, market:, intent:, ...)
    // -----------------------------------------------------------------

    private fun dispatchExternal(view: WebView?, uri: Uri) {
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            dispatchIntentUri(view, uri)
        } else {
            dispatchPlainExternal(uri)
        }
    }

    /**
     * Fire a plain external URI (mailto:, tel:, geo:, market:, ...) at
     * the system. There is no meaningful fallback for these, so a
     * missing handler just earns a toast.
     */
    private fun dispatchPlainExternal(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app handles " + uri.scheme + ": " + uri)
            toastNoHandler(uri.scheme)
        }
    }

    /**
     * Chrome-compatible handling for intent:// links.
     *
     * A real link looks like
     *
     *   intent://scan/#Intent;scheme=zxing;package=...;
     *     S.browser_fallback_url=https%3A%2F%2Fexample.com%2F;end
     *
     * The part after "#Intent;" is a serialized Intent object, so the
     * URI must be decoded with Intent.parseUri(). The previous code
     * handed the raw URI to a generic ACTION_VIEW intent, which can
     * never resolve to anything -- every intent: link died with "No
     * app found to open intent links".
     *
     * Implemented policy:
     *   1. Decode the URI with URI_INTENT_SCHEME.
     *   2. Read browser_fallback_url out of the decoded extras.
     *   3. Sanitise before launch (see sanitizeIntentForWeb).
     *   4. An embedded plain web URL with no package= is just a web
     *      navigation in disguise -- load it in this tab instead of
     *      launching a second browser task (possibly this browser).
     *   5. Launch. If nothing can handle it, open the fallback URL,
     *      but only when it is http/https (never file:, content:,
     *      javascript: or nested intent: URIs).
     *   6. No handler and no usable fallback: toast, stay put.
     */
    private fun dispatchIntentUri(view: WebView?, uri: Uri) {
        val parsed = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Malformed intent URI (" + e.message + "): " + uri)
            toastNoHandler(uri.scheme)
            return
        }

        // Chrome contract: the fallback web URL rides in the extras as
        // a string. Read it before anything else touches the intent.
        val fallbackUrl = parsed.getStringExtra(EXTRA_BROWSER_FALLBACK_URL)

        val launch = sanitizeIntentForWeb(parsed)

        // "intent://example.com/#Intent;scheme=https;end" with no
        // package= is a web navigation in disguise. Browsers own
        // those: keep it in this tab rather than launching a second
        // browser task (possibly this very browser again).
        val innerWebUrl = launch.data?.toString()
        if (launch.`package` == null &&
            innerWebUrl != null &&
            isWebUrl(innerWebUrl) &&
            view != null
        ) {
            loadFallbackUrl(view, innerWebUrl)
            return
        }

        try {
            appContext.startActivity(launch)
        } catch (e: ActivityNotFoundException) {
            if (view != null && fallbackUrl != null && isWebUrl(fallbackUrl)) {
                Log.w(TAG, "No app for intent URI, using fallback: " + uri)
                loadFallbackUrl(view, fallbackUrl)
            } else {
                Log.w(TAG, "No app handles intent: " + uri)
                toastNoHandler(uri.scheme)
            }
        } catch (e: SecurityException) {
            // Should be unreachable after sanitising; guarded anyway
            // because a crash inside shouldOverrideUrlLoading would
            // take the whole browser down with it.
            Log.w(TAG, "Refused to launch intent: " + uri + " (" + e.message + ")")
            toastNoHandler(uri.scheme)
        }
    }

    /**
     * Strip everything a hostile page could abuse out of an Intent
     * decoded from a URI. This mirrors the hardening Chrome applies:
     *
     *   - CATEGORY_BROWSABLE is forced on, so only activities that
     *     explicitly volunteer for browser-initiated launches match.
     *   - The component (and any selector's component) is cleared: a
     *     web page must never be able to aim an intent at a specific
     *     -- possibly non-exported -- activity of another app. That is
     *     the classic intent-redirection attack.
     *   - sourceBounds and clipData are dropped, and every flag the
     *     URI carried (launchFlags=...) is discarded. The only flag we
     *     re-add is NEW_TASK, which launching from an application
     *     context requires.
     */
    private fun sanitizeIntentForWeb(intent: Intent): Intent {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector?.let { selector ->
            selector.addCategory(Intent.CATEGORY_BROWSABLE)
            selector.component = null
        }
        intent.sourceBounds = null
        intent.clipData = null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return intent
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    /**
     * Load a fallback web URL in the tab that triggered the intent,
     * with the same treatment an address-bar navigation gets: the
     * per-host desktop UA is applied first and tracking parameters
     * are stripped. Posted because we are inside
     * shouldOverrideUrlLoading, which must return before the load it
     * schedules may start (same pattern as the UrlCleaner rewrite
     * in the http/https branch).
     */
    private fun loadFallbackUrl(view: WebView, fallbackUrl: String) {
        val host = try {
            URI(fallbackUrl).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
        if (host != null) {
            WebViewConfigurator.applyUserAgent(
                appContext, view, prefs.isDesktopHost(host)
            )
        }
        val cleaned = UrlCleaner.clean(fallbackUrl, prefs.removeParams)
        view.post {
            try { view.loadUrl(cleaned) } catch (_: Throwable) {}
        }
    }

    private fun toastNoHandler(scheme: String?) {
        Toast.makeText(
            appContext,
            "No app found to open " + (scheme ?: "this") + " links",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun serveHomePage(): WebResourceResponse {
        val html = loadHomeHtml(appContext)
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-cache"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view == null) return
        blockedCounter.set(0)
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
        // Inject vault detection for EVERY tab that finishes loading,
        // not just the active one. A background tab that finishes
        // loading must already have the detection script installed
        // so the pill appears the moment the user switches to it.
        VaultPillController.injectDetection(view, url)
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
        if (request?.isForMainFrame != true) return
        val desc = error?.description?.toString().orEmpty()
        // ERR_ABORTED is what WebView reports when WE cancelled the load
        // ourselves: a UrlCleaner rewrite (shouldOverrideUrlLoading
        // returning true + posting loadUrl), a desktop-mode UA switch, or
        // back-navigation away from a half-loaded page. It is not a real
        // failure -- surfacing it here flashed the "Can't open this
        // page" error screen over the very navigation we had just
        // started, and left a spurious error page in the back history.
        if (desc.contains("ERR_ABORTED", ignoreCase = true)) return
        ui.onPageLoadError(
            view,
            desc.ifBlank { "Page failed to load" },
            request.url?.toString()
        )
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
