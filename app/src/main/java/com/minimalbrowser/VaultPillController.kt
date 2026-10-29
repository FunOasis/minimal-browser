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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    // Dedup guard so a duplicate capture (both click + submit firing) only
    // shows one save dialog.
    @Volatile private var lastSaveKey: String = ""
    @Volatile private var lastSaveTime: Long = 0L

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

    /**
     * Called from the capture script when a login form is submitted.
     * Shows the "Save password?" dialog when appropriate:
     *  - both username and password are non-empty
     *  - no identical credential is already stored
     *  - the user has not marked the host "never save"
     *  - we have not already shown the same dialog for the same values
     *    within the last 3 seconds
     */
    fun onCredentialsCaptured(caller: WebView, username: String, password: String) {
        ui.post {
            if (username.isBlank() || password.isBlank()) return@post
            val current = currentWebView()
            if (current == null || caller != current) return@post
            val url = current.url ?: return@post
            val h = hostOf(url) ?: return@post
            val normalised = h.removePrefix("www.")

            val existing = store.accountsForHost(normalised)
            if (existing.any { it.username == username && it.password == password }) {
                return@post
            }

            val prefs = Prefs.get(activity)
            if (prefs.isVaultNeverSaveHost(normalised)) return@post

            val now = System.currentTimeMillis()
            val key = "$normalised|$username|$password"
            if (key == lastSaveKey && now - lastSaveTime < 3000L) return@post
            lastSaveKey = key
            lastSaveTime = now

            showSaveDialog(normalised, username, password)
        }
    }

    private fun showSaveDialog(host: String, username: String, password: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.vault_save_title))
            .setMessage(activity.getString(R.string.vault_save_message, host, username))
            .setPositiveButton(R.string.vault_save_action) { _, _ ->
                store.save(host, username, password)
                Toast.makeText(
                    activity, R.string.vault_save_saved, Toast.LENGTH_SHORT
                ).show()
                currentWebView()?.let { onPasswordFieldDetected(it) }
            }
            .setNeutralButton(R.string.vault_save_never) { _, _ ->
                Prefs.get(activity).addVaultNeverSaveHost(host)
                Toast.makeText(
                    activity, R.string.vault_save_never_done, Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.vault_save_not_now, null)
            .show()
    }

    private fun showSheet() {
        val current = currentWebView() ?: return
        val url = current.url ?: return
        val h = hostOf(url) ?: return
        val accounts = store.accountsForHost(h)
        if (accounts.isEmpty()) {
            Toast.makeText(
                activity, "No saved passwords for this site", Toast.LENGTH_SHORT
            ).show()
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

        /**
         * Capture script. Fires onCredentialsCaptured when a login form
         * is submitted — via a submit button click, the Enter key, or a
         * JS-driven submit() call. Uses capture-phase listeners so we
         * see the values before the page's own preventDefault() can
         * clear them. Values are read synchronously at event time, not
         * after any delay.
         */
        val CAPTURE_JS: String = """
            (function() {
                if (window.__minimalBrowserVaultCaptureInstalled) return;
                window.__minimalBrowserVaultCaptureInstalled = true;

                var lastKey = '';
                var lastTime = 0;

                function sendCapture(user, pass) {
                    if (!pass) return;
                    var key = (user || '') + '||' + pass;
                    var now = Date.now();
                    if (key === lastKey && now - lastTime < 2000) return;
                    lastKey = key;
                    lastTime = now;
                    try {
                        window.MinimalBrowserVaultBridge.onCredentialsCaptured(user || '', pass);
                    } catch (e) {}
                }

                function findUsernameField(form) {
                    if (!form || !form.querySelector) return null;
                    var pw = form.querySelector('input[type="password"]');
                    if (!pw) return null;
                    var hints;
                    try {
                        hints = form.querySelectorAll(
                            'input[autocomplete="username"], ' +
                            'input[autocomplete="email"], ' +
                            'input[type="email"], ' +
                            'input[name*="user"], input[name*="email"], input[name*="login"], ' +
                            'input[id*="user"], input[id*="email"], input[id*="login"]'
                        );
                    } catch (e) { hints = []; }
                    for (var i = 0; i < hints.length; i++) {
                        var h = hints[i];
                        if (h === pw) continue;
                        if (h.type === 'hidden' || h.disabled) continue;
                        if (!h.offsetParent && h.offsetWidth === 0) continue;
                        return h;
                    }
                    var all;
                    try {
                        all = form.querySelectorAll('input[type="text"], input[type="email"]');
                    } catch (e) { return null; }
                    var candidate = null;
                    for (var j = 0; j < all.length; j++) {
                        var c = all[j];
                        if (c === pw) continue;
                        if (!c.offsetParent && c.offsetWidth === 0) continue;
                        if (c.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING) {
                            candidate = c;
                        }
                    }
                    return candidate;
                }

                function readFromForm(form) {
                    var pw = form.querySelector('input[type="password"]');
                    if (!pw || !pw.value) return;
                    var userField = findUsernameField(form);
                    sendCapture(userField ? userField.value : '', pw.value);
                }

                document.addEventListener('submit', function(e) {
                    try {
                        var form = e.target;
                        if (!form || form.tagName !== 'FORM') return;
                        readFromForm(form);
                    } catch (err) {}
                }, true);

                document.addEventListener('keydown', function(e) {
                    try {
                        if (e.key !== 'Enter') return;
                        var t = e.target;
                        if (!t || t.tagName !== 'INPUT' || t.type !== 'password') return;
                        if (!t.value) return;
                        var form = t.form;
                        if (form) {
                            var userField = findUsernameField(form);
                            sendCapture(userField ? userField.value : '', t.value);
                        } else {
                            sendCapture('', t.value);
                        }
                    } catch (err) {}
                }, true);

                document.addEventListener('click', function(e) {
                    try {
                        var t = e.target;
                        if (!t || !t.closest) return;
                        var btn = t.closest('button[type="submit"], input[type="submit"]');
                        if (!btn) return;
                        var form = btn.form || btn.closest('form');
                        if (!form) return;
                        var pw = form.querySelector('input[type="password"]');
                        if (!pw || !pw.value) return;
                        var userField = findUsernameField(form);
                        var u = userField ? userField.value : '';
                        var p = pw.value;
                        setTimeout(function() { sendCapture(u, p); }, 50);
                    } catch (err) {}
                }, true);
            })();
        """.trimIndent()

        fun injectDetection(webView: WebView, url: String?) {
            if (url.isNullOrBlank()) return
            if (url.startsWith(Prefs.HOME_URL)) return
            if (url.startsWith("data:") || url.startsWith("about:")) return
            try {
                webView.evaluateJavascript(DETECTION_JS, null)
                webView.evaluateJavascript(CAPTURE_JS, null)
            } catch (_: Throwable) {}
        }
    }
}
