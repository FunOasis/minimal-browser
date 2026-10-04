package com.minimalbrowser

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Single source of truth for WebView security + performance + stability
 * settings.
 *
 * Everything here is conservative on purpose: a browser that renders
 * arbitrary third-party pages has to assume the page is hostile until
 * proven otherwise. Loosen a flag only with a comment explaining why.
 */
object WebViewConfigurator {

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
        //
        // allowFileAccess + allowContentAccess = false is the single
        // switch that disables file:// and content:// URL loading. The
        // older granular flags (allowFileAccessFromFileURLs,
        // allowUniversalAccessFromFileURLs) were deprecated in API 30
        // and now have no effect whenever allowFileAccess is false —
        // which is exactly our case. So we don't set them anymore.
        s.allowFileAccess = false
        s.allowContentAccess = false

        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        // --- Stability (crash mitigation) -------------------------------
        //
        // Turn OFF Chromium's algorithmic darkening. When the system
        // forces dark mode onto a light-themed page, WebView runs a CSS
        // color-inversion pass that — on several Adreno driver versions
        // shipped with HyperOS / MIUI — dereferences a null buffer and
        // segfaults inside libGLESv2_adreno.so. Our app is dark-only,
        // so we never want the page darkened anyway. This single flag
        // removes the most common crash trigger we've seen.
        //
        // androidx.webkit handles the version dance for us:
        //   API 33+ → setAlgorithmicDarkeningAllowed(false)
        //   API 29-32 → setForceDark(FORCE_DARK_OFF)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }

        // Pin the WebView to the hardware layer explicitly. This is the
        // default but being explicit ensures the compositor treats the
        // surface consistently with the rest of our UI.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // --- Cookies ----------------------------------------------------
        // First-party yes, third-party no. Massive privacy and
        // ad-tracking win, and near-zero breakage for normal browsing.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
    }
}
