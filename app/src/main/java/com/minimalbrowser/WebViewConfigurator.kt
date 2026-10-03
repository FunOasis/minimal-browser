package com.minimalbrowser

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Single source of truth for WebView security + performance settings.
 *
 * Everything here is conservative on purpose: a browser that renders
 * arbitrary third-party pages has to assume the page is hostile until
 * proven otherwise. Loosen a flag only with a comment explaining why.
 */
object WebViewConfigurator {

    /**
     * Applies default settings to [webView]. [javaScriptEnabled] is passed
     * in rather than read from Prefs so this class stays Android-only and
     * doesn't reach into the prefs store.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView, javaScriptEnabled: Boolean) {
        val s: WebSettings = webView.settings

        // --- Behaviour the user expects from a browser ------------------
        s.javaScriptEnabled    = javaScriptEnabled
        s.domStorageEnabled    = true              // required by most modern sites
        s.loadWithOverviewMode = true
        s.useWideViewPort      = true
        s.builtInZoomControls  = true
        s.displayZoomControls  = false

        // --- Security hardening -----------------------------------------
        // Local file/content access: only needed if you support file:// or
        // content:// URLs. We don't, and leaving them on lets a hostile page
        // read the user's downloads and media store via injected JS.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false

        // Refuse to load http:// subresources on https:// pages. The user
        // can still navigate to http:// top-level (usesCleartextTraffic=true
        // in the manifest), we just won't silently downgrade a secure page.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Google's phishing / malware list, API 26+ (our minSdk).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        // Cookies: first-party yes, third-party no. Massive privacy and
        // ad-tracking win, and near-zero breakage for normal browsing.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
    }
}
