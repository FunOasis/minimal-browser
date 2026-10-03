package com.minimalbrowser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

interface BrowserUiListener {
    fun onUrlChanged(url: String)
    fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean)
    fun onPageLoadStarted()
    fun onPageLoadFinished()
    fun onPageLoadError(description: String, url: String?)
    fun onTitleChanged(title: String)
    fun onFaviconReceived(icon: Bitmap?)
}

class BlockingWebViewClient(
    private val blocker: AdBlocker,
    private val appContext: Context,
    private val ui: BrowserUiListener
) : WebViewClient() {

    companion object {
        private const val TAG = "BlockingWebViewClient"
        private val EMPTY_BODY = ByteArray(0)

        private val EXTERNAL_SCHEMES = setOf(
            "mailto", "tel", "sms", "smsto", "mms", "mmsto",
            "geo", "market", "intent"
        )

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content", emptyMap(),
            ByteArrayInputStream(EMPTY_BODY)
        )
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url.toString()
        return if (blocker.isBlocked(url)) blockedResponse() else null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase() ?: return true

        return when (scheme) {
            "http", "https", "about" -> false

            in EXTERNAL_SCHEMES -> {
                dispatchExternal(uri)
                true
            }

            else -> {
                Log.w(TAG, "Refusing navigation with scheme '$scheme'")
                true
            }
        }
    }

    private fun dispatchExternal(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app handles ${uri.scheme}: $uri")
            ui.onPageLoadError("No app can open this link", uri.toString())
        }
    }

    // --- Lifecycle callbacks that keep the UI in sync ----------------------

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        ui.onPageLoadStarted()
        if (url != null) ui.onUrlChanged(url)
        ui.onNavStateChanged(view?.canGoBack() == true, view?.canGoForward() == true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        ui.onPageLoadFinished()
        if (url != null) ui.onUrlChanged(url)
        ui.onNavStateChanged(view?.canGoBack() == true, view?.canGoForward() == true)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (url != null) ui.onUrlChanged(url)
        ui.onNavStateChanged(view?.canGoBack() == true, view?.canGoForward() == true)
    }

    /**
     * Fires as the page's <title> element is parsed or updated. A page
     * may fire this multiple times during load (initial title, then
     * JS-updated title), which is expected — we just forward the latest.
     */
    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (title != null) ui.onTitleChanged(title)
    }

    /**
     * Fires when the page's favicon is available. Not every page has one,
     * in which case this is never called and the UI keeps whatever it
     * was showing. When the page *clears* its favicon (rare), the OS
     * sends null — the UI should handle that by clearing too.
     */
    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        ui.onFaviconReceived(icon)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val desc = error?.description?.toString() ?: "Page failed to load"
            ui.onPageLoadError(desc, request.url?.toString())
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler,
        error: SslError?
    ) {
        Log.w(TAG, "SSL error, refusing to proceed: ${error?.primaryError}")
        handler.cancel()
    }
}
