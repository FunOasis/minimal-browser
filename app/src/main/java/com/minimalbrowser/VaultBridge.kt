package com.minimalbrowser

import android.webkit.JavascriptInterface
import android.webkit.WebView

class VaultBridge(
    private val controller: VaultPillController,
    private val webView: WebView
) {
    @JavascriptInterface
    fun onPasswordFieldDetected() {
        controller.onPasswordFieldDetected(webView)
    }

    @JavascriptInterface
    fun onPasswordFieldFocused() {
        controller.onPasswordFieldDetected(webView)
    }
}
