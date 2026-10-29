package com.minimalbrowser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URI

class VaultPillController(
    private val activity: MainActivity,
    private val store: SecureCredentialStore,
    private val currentWebView: () -> WebView?
) {

    private var host: FrameLayout? = null
    private var pill: View? = null
    private var sheetDialog: Dialog? = null
    private val ui = Handler(Looper.getMainLooper())
    private var pendingClipboardClear: Runnable? = null

    fun setHost(newHost: FrameLayout?) {
        if (this.host != null && pill != null && pill?.parent == this.host) {
            this.host?.removeView(pill)
        }
        this.host = newHost
        if (newHost != null) {
            if (pill == null) pill = buildPill()
            newHost.addView(pill, buildPillParams())
            pill?.visibility = View.GONE
        }
    }

    private fun buildPill(): View {
        val tv = TextView(activity).apply {
            text = "Vault"
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = activity.getDrawable(R.drawable.bg_vault_pill)
            setOnClickListener { showSheet() }
        }
        return tv
    }

    private fun buildPillParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.marginEnd = dp(16)
        lp.bottomMargin = dp(16)
        return lp
    }

    fun onPasswordFieldDetected(caller: WebView) {
        ui.post {
            val current = currentWebView()
            if (current == null || caller != current) return@post
            val url = current.url ?: return@post
            val h = hostOf(url) ?: return@post
            val accounts = store.accountsForHost(h)
            pill?.visibility = if (accounts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun hidePill() {
        pill?.visibility = View.GONE
    }

    private fun showSheet() {
        val current = currentWebView() ?: return
        val url = current.url ?: return
        val h = hostOf(url) ?: return
        val accounts = store.accountsForHost(h)
        if (accounts.isEmpty()) {
            Toast.makeText(activity, "No saved passwords for this site", Toast.LENGTH_SHORT).show()
            return
        }

        val root = buildSheetContent(h, accounts, current)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.45f)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.BOTTOM)
            }
        }
        dialog.show()
        sheetDialog = dialog
    }

    @SuppressLint("SetTextI18n")
    private fun buildSheetContent(
        host: String,
        accounts: List<SecureCredentialStore.Credential>,
        webView: WebView
    ): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = activity.getDrawable(R.drawable.bg_sheet)
        }

        val title = TextView(activity).apply {
            text = "Saved accounts for $host"
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val scroll = ScrollView(activity)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll)

        for (c in accounts) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = activity.getDrawable(R.drawable.bg_vault_row)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            row.layoutParams = lp

            val userTv = TextView(activity).apply {
                text = c.username
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(userTv)

            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(actionText("Copy user") { copyToClipboard(c.username, "Username copied") })
            actions.addView(actionText("Copy password") { copyToClipboard(c.password, "Password copied") })
            actions.addView(actionText("Use") { fillCredentials(webView, c) })
            row.addView(actions)

            list.addView(row)
        }

        return root
    }

    private fun actionText(label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            setTextColor(activity.getColor(R.color.accent))
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }

    private fun fillCredentials(webView: WebView, credential: SecureCredentialStore.Credential) {
        val js = """
            (function() {
                var passFields = document.querySelectorAll('input[type="password"]');
                if (passFields.length === 0) return;
                var userFields = document.querySelectorAll(
                    'input[type="text"], input[type="email"], input[name*="user"], input[name*="email"], input[id*="user"], input[id*="email"]'
                );
                var u = ${JSONObject.quote(credential.username)};
                var p = ${JSONObject.quote(credential.password)};
                function setVal(el, v) {
                    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, v);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                if (userFields.length > 0) setVal(userFields[0], u);
                for (var i = 0; i < passFields.length; i++) setVal(passFields[i], p);
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(js, null)
            Toast.makeText(activity, "Credentials filled", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(activity, "Could not fill form", Toast.LENGTH_SHORT).show()
        }
        sheetDialog?.dismiss()
    }

    private fun copyToClipboard(value: String, message: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("credential", value))
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            scheduleClipboardClear(cm)
        } catch (_: Throwable) {
            Toast.makeText(activity, "Copy failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleClipboardClear(cm: ClipboardManager) {
        pendingClipboardClear?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Throwable) {}
        }
        pendingClipboardClear = r
        ui.postDelayed(r, 60_000L)
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val JS_BRIDGE_NAME = "MinimalBrowserVaultBridge"

        val DETECTION_JS: String = """
            (function() {
                if (window.__minimalBrowserVaultInstalled) return;
                window.__minimalBrowserVaultInstalled = true;
                function hasPassword() {
                    return document.querySelector('input[type="password"]') !== null;
                }
                function notify() {
                    if (!hasPassword()) return;
                    try { window.MinimalBrowserVaultBridge.onPasswordFieldDetected(); } catch (e) {}
                }
                document.addEventListener('focusin', function(e) {
                    var t = e.target;
                    if (t && t.tagName === 'INPUT' && t.type === 'password') {
                        try { window.MinimalBrowserVaultBridge.onPasswordFieldFocused(); } catch (err) {}
                    }
                }, true);
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', notify);
                } else {
                    notify();
                }
                setTimeout(notify, 1500);
            })();
        """.trimIndent()

        fun injectDetection(webView: WebView, url: String?) {
            if (url.isNullOrBlank()) return
            if (url.startsWith(Prefs.HOME_URL)) return
            if (url.startsWith("data:") || url.startsWith("about:")) return
            try {
                webView.evaluateJavascript(DETECTION_JS, null)
            } catch (_: Throwable) {}
        }
    }
}
