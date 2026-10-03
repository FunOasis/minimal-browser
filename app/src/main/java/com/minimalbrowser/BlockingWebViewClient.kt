package com.minimalbrowser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class BlockingWebViewClient(private val blocker: AdBlocker) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        return if (blocker.isBlocked(url)) {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        } else null
    }
}
