package com.minimalbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Pins a launcher shortcut for the current page.
 *
 * Uses the modern ShortcutManagerCompat API (shortcut pinning was added in
 * API 25 / Nougat; our minSdk is 26, so it is always available). The
 * launcher asks the user to confirm, then places an icon on the home
 * screen. Tapping the icon launches MainActivity directly with the target
 * URL as an extra — no chooser dialog, because the intent is explicit.
 *
 * We deliberately avoid the shortcut.xml static-declaration path. Runtime
 * pinning is the only way to create a shortcut *for an arbitrary URL the
 * user just browsed to*; XML only supports a fixed, compile-time list.
 */
object ShortcutHelper {

    /** Max lengths enforced by the framework for shortcut labels. */
    private const val SHORT_LABEL_MAX = 40
    private const val LONG_LABEL_MAX = 80

    /**
     * Attempts to pin a shortcut. Returns one of three outcomes so the
     * caller can show the right toast.
     */
    enum class Result { PINNED, UNSUPPORTED, INVALID }

    fun requestPin(
        context: Context,
        url: String,
        title: String,
        favicon: Bitmap?
    ): Result {
        if (url.isBlank() || url.startsWith("minimal://")) return Result.INVALID

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return Result.UNSUPPORTED
        }

        val label = resolveLabel(title, url)
        val icon = resolveIcon(context, favicon)
        val shortcutId = "mb_page_${url.hashCode()}"

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SHORTCUT_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(SHORT_LABEL_MAX))
            .setLongLabel(label.take(LONG_LABEL_MAX))
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        val pinned = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        return if (pinned) Result.PINNED else Result.UNSUPPORTED
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun resolveLabel(title: String, url: String): String {
        val t = title.trim()
        if (t.isNotBlank()) return t

        // Fall back to the hostname with "www." stripped.
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.substringBefore('.')
        if (!host.isNullOrBlank()) return host.replaceFirstChar { it.uppercase() }

        return "Minimal Browser"
    }

    private fun resolveIcon(context: Context, favicon: Bitmap?): IconCompat =
        if (favicon != null && !favicon.isRecycled) {
            IconCompat.createWithBitmap(favicon)
        } else {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
}
