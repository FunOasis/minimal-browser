package com.minimalbrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Status-bar notifications for in-process downloads.
 *
 * The old code relied on DownloadManager to show progress. Since we
 * moved the download into our own process, we own the notification now.
 *
 * A single ongoing notification is reused across downloads. It is
 * updated with determinate progress as chunks arrive, then replaced by
 * a completion or failure notification that auto-cancels.
 *
 * Channel is created lazily on API 26+. On API 33+ the notification
 * only shows if POST_NOTIFICATIONS has been granted at runtime; the
 * manifest declares the permission and MainActivity requests it.
 */
object DownloadNotifications {

    private const val TAG = "DownloadNotif"
    private const val CHANNEL_ID = "mb_downloads"
    private const val CHANNEL_NAME = "Downloads"
    private const val CHANNEL_DESC = "In-progress and completed file downloads"
    private const val NOTIFICATION_ID = 0x4D42001

    fun start(context: Context, fileName: String, totalBytes: Long) {
        ensureChannel(context)
        val builder = base(context)
            .setContentTitle(context.getString(R.string.download_started, fileName))
            .setProgress(100, 0, totalBytes <= 0L)
            .setOngoing(true)
        post(context, builder.build())
    }

    fun progress(context: Context, fileName: String, bytes: Long, total: Long) {
        if (total <= 0L) return
        val pct = ((bytes * 100L) / total).toInt().coerceIn(0, 100)
        val builder = base(context)
            .setContentTitle(context.getString(R.string.download_started, fileName))
            .setProgress(100, pct, false)
            .setOngoing(true)
        post(context, builder.build())
    }

    fun complete(context: Context, fileName: String, success: Boolean) {
        val title = if (success) {
            context.getString(R.string.download_saved, fileName)
        } else {
            context.getString(R.string.download_failed) + ": " + fileName
        }
        val builder = base(context)
            .setContentTitle(title)
            .setOngoing(false)
            .setAutoCancel(true)
        post(context, builder.build())
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

    private fun post(context: Context, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; drop silently.
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "notify failed: " + t.message)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }
}
