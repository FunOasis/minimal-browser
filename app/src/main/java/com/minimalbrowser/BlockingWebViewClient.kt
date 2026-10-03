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

/**
 * Callbacks the client fires at the host Activity so UI stays in sync.
 * Title and favicon are NOT here — those come from WebChromeClient in
 * MainActivity, since WebViewClient doesn't expose them.
 */
interface BrowserUiListener {
    fun onUrlChanged(url: String)
    fun onNavStateChanged(canGoBack: Boolean, canGoForward: Boolean)
    fun onPageLoadStarted()
    fun onPageLoadFinished()
    fun onPageLoadError(description: String, url: String?)
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
