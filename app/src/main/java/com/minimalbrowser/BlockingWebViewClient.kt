package com.minimalbrowser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class BlockingWebViewClient(private val blocker: AdBlocker) : WebViewClient() {

    companion object {
        private val EMPTY_BODY = ByteArray(0)

        /**
         * 204 No Content tells the renderer "nothing to display" without
         * firing an error handler. We build a fresh response per call —
         * the stream position on a shared instance would be exhausted
         * after the first read, and this allocation is trivial next to
         * the URL parse that already happened upstream.
         */
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
}
