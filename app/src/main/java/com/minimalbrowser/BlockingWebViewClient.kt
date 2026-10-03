package com.minimalbrowser

import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class BlockingWebViewClient(private val blocker: AdBlocker) : WebViewClient() {

    companion object {
        private const val TAG = "BlockingWebViewClient"
        private val EMPTY_BODY = ByteArray(0)

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(EMPTY_BODY)
        )
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Never block top-level navigations. If the user explicitly typed
        // or tapped a URL they want it — blocking the main frame without
        // a fallback page leaves a blank WebView with no recovery path.
        if (request.isForMainFrame) return null

        val url = request.url.toString()
        return if (blocker.isBlocked(url)) blockedResponse() else null
    }

    /**
     * Never proceed past an SSL error. The default WebView behaviour is
     * to cancel already, but making it explicit protects against future
     * edits and documents the decision for anyone reading this file.
     */
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler,
        error: SslError?
    ) {
        Log.w(TAG, "SSL error, refusing to proceed: ${error?.primaryError}")
        handler.cancel()
    }
}
