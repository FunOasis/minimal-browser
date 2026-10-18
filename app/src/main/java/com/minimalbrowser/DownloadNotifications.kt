package com.minimalbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object DownloadNotifications {

    private const val TAG = "DownloadNotif"
    private const val CHANNEL_ID = "mb_downloads_v2"
    private const val CHANNEL_NAME = "Downloads"
    private const val CHANNEL_DESC = "In-progress and completed file downloads"
    private const val NOTIFICATION_ID_BASE = 0x4D42001

    /** One notification slot per active file, so two downloads do not stomp each other. */
    private fun idFor(fileName: String): Int =
        NOTIFICATION_ID_BASE + (fileName.hashCode() and 0x00FFFFFF)

    fun start(context: Context, fileName: String, totalBytes: Long) {
        ensureChannel(context)
        val n = base(context)
            .setContentTitle(context.getString(R.string.download_started, fileName))
            .setProgress(100, 0, totalBytes <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        post(context, idFor(fileName), n)
    }

    fun progress(context: Context, fileName: String, bytes: Long, total: Long) {
        if (total <= 0L) return
        val pct = ((bytes * 100L) / total).toInt().coerceIn(0, 100)
        val n = base(context)
            .setContentTitle(context.getString(R.string.download_started, fileName))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        post(context, idFor(fileName), n)
    }

    fun complete(context: Context, fileName: String, success: Boolean) {
        val title = if (success) {
            context.getString(R.string.download_saved, fileName)
        } else {
            context.getString(R.string.download_failed) + ": " + fileName
        }
        val n = base(context)
            .setContentTitle(title)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        post(context, idFor(fileName), n)
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun post(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: " + t.message)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }
}
