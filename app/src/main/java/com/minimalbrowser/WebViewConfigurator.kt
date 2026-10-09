package com.minimalbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Single source of truth for WebView security + performance + stability
 * settings, plus per-host user-agent switching for desktop mode.
 */
object WebViewConfigurator {

    /**
     * Chrome desktop UA. Desktop sites respond primarily to the UA
     * string; this is the same one Chrome's "Request desktop site"
     * toggle uses, minus the platform-specific part.
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile private var cachedMobileUa: String? = null

    /**
     * System default UA, cached on first call. WebSettings.getDefault
     * UserAgent is not free -- it constructs a string and touches the
     * package manager -- so we only call it once per process.
     */
    fun mobileUserAgent(context: Context): String {
        cachedMobileUa?.let { return it }
        val ua = try { WebSettings.getDefaultUserAgent(context) }
                 catch (_: Throwable) { "" }
        cachedMobileUa = ua
        return ua
    }

/**
 * Set the UA on a WebView for desktop or mobile, and adjust the
 * viewport handling to match.
 *
 * Desktop mode requires more than a UA change. Pages that ship a
 * mobile viewport meta (<meta name="viewport" content="width=
 * device-width">) will still lay out narrow and fire mobile CSS
 * media queries even with a desktop UA. Turning useWideViewPort
 * off makes the layout engine ignore that meta and fall back to
 * its own wide default viewport (~980 CSS px), which is what
 * triggers desktop CSS on responsive sites. This is roughly how
 * Chrome's "Request desktop site" behaves.
 */
fun applyUserAgent(context: Context, webView: WebView, desktop: Boolean) {
    val s = webView.settings
    val target = if (desktop) DESKTOP_UA else mobileUserAgent(context)
    if (target.isNotBlank() && s.userAgentString != target) {
        s.userAgentString = target
    }
    if (desktop) {
        s.useWideViewPort = false
        s.loadWithOverviewMode = true
    } else {
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
    }
}

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView, javaScriptEnabled: Boolean) {
        val s: WebSettings = webView.settings

        // --- Behaviour the user expects from a browser ------------------
        s.javaScriptEnabled    = javaScriptEnabled
        s.domStorageEnabled    = true
        s.databaseEnabled      = true
        s.loadWithOverviewMode = true
        s.useWideViewPort      = true
        s.builtInZoomControls  = true
        s.displayZoomControls  = false

        // --- Cache -------------------------------------------------------
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // --- Security hardening -----------------------------------------
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }

        // --- Stability (crash mitigation) -------------------------------
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // --- GPU relief --------------------------------------------------
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // --- Cookies ----------------------------------------------------
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
    }
}
