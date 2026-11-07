package com.minimalbrowser

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.text.InputType
import android.util.Log
import android.util.Base64
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

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
    private val ui: BrowserUiListener,
    hostActivity: MainActivity
) : WebViewClient() {

    // Held weak: this client is attached to a WebView whose lifetime can
    // outlast a single Activity instance in edge cases (locale changes
    // still recreate the Activity per MainActivity's configChanges
    // comment). Only used to host two confirmation dialogs below; never
    // dereferenced off the UI thread.
    private val activityRef = WeakReference(hostActivity)
    private fun currentActivity(): MainActivity? =
        activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

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
    // Home page must be served from the packaged asset. Must come
    // BEFORE the "return null for main frame" shortcut, otherwise
    // WebView tries to load minimal://home over the network and the
    // page comes up blank.    
        if (request.isForMainFrame && url.startsWith(HOME_URL)) {        
            return serveHomePage()    
        }
    
        if (request.isForMainFrame) return null
    
        if (blocker.isBlocked(url)) {        
            blockedCounter.incrementAndGet()        
            return blockedResponseFor(url)    
        }    
        return null
    }

/**
 * 1×1 transparent PNG, decoded once from Base64.
 * Using Base64 avoids the Byte-literal problem entirely and is guaranteed to be
 * a valid PNG the WebView can render.
 */
private val transparentPng: ByteArray by lazy {
    Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        Base64.DEFAULT
    )
}

/**
 * For a blocked sub-resource, return 200 OK with a valid but empty body.
 * - images  → 1×1 transparent PNG
 * - others  → empty HTML document
 *
 * This prevents the WebView from rendering its built-in
 * "Web page not available" error page inside iframes.
 */
private fun blockedResponseFor(url: String): WebResourceResponse {
    val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico")

    val mime: String
    val body: ByteArray
    if (isImage) {
        mime = "image/png"
        body = transparentPng
    } else {
        mime = "text/html"
        body = "<!DOCTYPE html><html><head></head><body></body></html>"
            .toByteArray(Charsets.UTF_8)
    }

    return WebResourceResponse(
        mime,
        "utf-8",
        200,
        "OK",
        emptyMap(),
        ByteArrayInputStream(body)
    )
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
        val host = error?.url?.let { urlToHost(it) }
        if (error != null && host != null && LocalNetwork.isLocalOrPrivateHost(host)) {
            // Router and ISP-modem admin pages on the local network very
            // commonly serve HTTPS with a self-signed certificate. Ask
            // the user instead of silently failing shut -- but only for
            // a host on a private/loopback/link-local address or a
            // ".local" name, never for a public-internet host, so this
            // never weakens certificate checking for ordinary browsing.
            Log.w(TAG, "SSL error on local host " + host + ": " + error.primaryError)
            showSslBypassDialog(handler, host, error)
            return
        }
        Log.w(TAG, "SSL error, refusing to proceed: " + error?.primaryError)
        handler.cancel()
    }

    private fun urlToHost(url: String): String? =
        try { URI(url).host } catch (_: Exception) { null }

    private fun sslErrorDescription(code: Int): String = when (code) {
        SslError.SSL_NOTYETVALID -> "certificate not yet valid"
        SslError.SSL_EXPIRED -> "certificate expired"
        SslError.SSL_IDMISMATCH -> "certificate doesn't match this address"
        SslError.SSL_UNTRUSTED -> "untrusted or self-signed certificate"
        SslError.SSL_DATE_INVALID -> "device clock looks wrong"
        SslError.SSL_INVALID -> "invalid certificate"
        else -> "certificate could not be verified"
    }

    private fun showSslBypassDialog(handler: SslErrorHandler, host: String, error: SslError) {
        val activity = currentActivity()
        if (activity == null) {
            handler.cancel()
            return
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle("Can't verify " + host)
                .setMessage(
                    host + " is on your local network, and its security " +
                        "certificate could not be verified (" +
                        sslErrorDescription(error.primaryError) + "). This is " +
                        "normal for routers and modems, which usually sign " +
                        "their own certificate. Continue only if this is your " +
                        "own device."
                )
                .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .setCancelable(true)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not show SSL dialog: " + t.message)
            handler.cancel()
        }
    }

    /**
     * Router, ISP-modem and other local-network admin pages overwhelmingly
     * gate their web UI with plain HTTP Basic/Digest auth rather than an
     * HTML login form (there is no WebViewClient hook for a form -- the
     * page itself handles that -- this is specifically the browser-level
     * auth challenge). The stock WebViewClient.onReceivedHttpAuthRequest
     * always cancels, which made every such page unreachable through this
     * browser. This mirrors what every mainstream browser does: check for
     * credentials saved from a previous visit to this host+realm and
     * retry them silently; otherwise prompt.
     */
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        if (view == null || host.isNullOrBlank()) {
            handler.cancel()
            return
        }
        val savedRealm = realm.orEmpty()

        val stored = try {
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(appContext)
                .getHttpAuthUsernamePassword(host, savedRealm)
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP auth credential lookup failed: " + t.message)
            null
        }
        val storedUser = stored?.getOrNull(0)
        if (!storedUser.isNullOrEmpty()) {
            handler.proceed(storedUser, stored.getOrNull(1).orEmpty())
            return
        }

        showHttpAuthDialog(handler, host, savedRealm)
    }

    private fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String) {
        val activity = currentActivity()
        if (activity == null) {
            handler.cancel()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val userField = EditText(activity).apply {
            hint = "Username"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val passField = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(20)
            setPadding(padH, dp(12), padH, 0)
            addView(userField)
            addView(
                passField,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }

        val message = if (realm.isNotBlank()) {
            "Sign in to " + host + " (" + realm + ")"
        } else {
            "Sign in to " + host
        }

        try {
            AlertDialog.Builder(activity)
                .setTitle("Login required")
                .setMessage(message)
                .setView(layout)
                .setPositiveButton("Sign in") { _, _ ->
                    val user = userField.text?.toString().orEmpty()
                    val pass = passField.text?.toString().orEmpty()
                    try {
                        @Suppress("DEPRECATION")
                        WebViewDatabase.getInstance(appContext)
                            .setHttpAuthUsernamePassword(host, realm, user, pass)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not save HTTP auth credentials: " + t.message)
                    }
                    handler.proceed(user, pass)
                }
                .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .setCancelable(true)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not show HTTP auth dialog: " + t.message)
            handler.cancel()
        }
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
