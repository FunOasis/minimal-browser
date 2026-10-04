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
        s.databaseEnabled      = true              // IndexedDB / WebSQL — some SPAs need it
        s.loadWithOverviewMode = true
        s.useWideViewPort      = true
        s.builtInZoomControls  = true
        s.displayZoomControls  = false

        // --- Cache -------------------------------------------------------
        // LOAD_DEFAULT uses the disk HTTP cache normally, falling back to
        // the network when a resource is stale or missing. This is the
        // framework default but we set it explicitly so future changes
        // are visible in one place.
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // --- Security hardening -----------------------------------------
        //
        // allowFileAccess + allowContentAccess = false disables file://
        // and content:// URL loading. The older granular flags
        // (allowFileAccessFromFileURLs, allowUniversalAccessFromFileURLs)
        // were deprecated in API 30 and now have no effect whenever
        // allowFileAccess is false — which is exactly our case.
        s.allowFileAccess = false
        s.allowContentAccess = false

        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // We don't hold a location permission, so geolocation should
        // never be requested. Explicit false prevents WebView from
        // caching geolocation prompts the system will never approve.
        s.setGeolocationEnabled(false)

        // Block <video autoplay> without a user gesture. Saves CPU and
        // battery on news sites that autoplay preview clips, and stops
        // pages from pinning hardware decoders while the user reads.
        s.mediaPlaybackRequiresUserGesture = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        // --- Stability (crash mitigation) -------------------------------
        //
        // Turn OFF Chromium's algorithmic darkening. On several Adreno
        // driver versions shipped with HyperOS / MIUI the inversion pass
        // dereferences a null buffer and segfaults inside
        // libGLESv2_adreno.so. Our app is dark-only so we never want the
        // page darkened anyway.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }

        // Pin the WebView to the hardware layer explicitly.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // --- GPU relief --------------------------------------------------
        // Overscroll glow and scrollbar rendering both hit the same
        // Adreno driver path that crashed us. Neither is needed for a
        // full-screen browser, so we turn both off entirely.
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // --- Cookies ----------------------------------------------------
        // First-party yes, third-party no. Massive privacy and
        // ad-tracking win, and near-zero breakage for normal browsing.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
    }
}
