package com.minimalbrowser

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background refresh of the blocklist warehouse.
 *
 * Runs every 12 hours (WorkManager's minimum granularity is 15 minutes;
 * 12h is a deliberate compromise -- the store's own freshness check
 * still skips lists younger than 24h, so the second daily run is a
 * cheap timestamp-only no-op most of the time).
 *
 * Why not a coroutine in MinimalBrowserApp:
 *  - a coroutine dies when the process is killed; the user swiping the
 *    app away at 10pm would cancel a 2am scheduled refresh
 *  - Doze mode would defer or drop a bare coroutine
 *  - WorkManager survives process death, wakes on Doze exit, retries
 *    with exponential backoff on transient failures
 *
 * Network constraint is CONNECTED (any network). If you want to force
 * Wi-Fi-only refresh, change to NetworkType.UNMETERED below.
 */
class BlocklistRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val store = BlocklistStore.get(applicationContext)
            val summary = store.refreshAll(force = false)
            Log.i(TAG, "Background refresh: refreshed=" + summary.refreshed +
                " failed=" + summary.failed +
                " skipped=" + summary.skipped +
                " totalHosts=" + summary.totalHosts)

            // If anything actually changed on disk, rebuild the in-memory
            // trie so the currently running process picks up the new hosts.
            // If the process is dead, this is a harmless no-op: the next
            // launch will rebuild from disk anyway.
            if (summary.refreshed > 0) {
                AdBlocker.get(applicationContext).reloadCustomRules()
            }

            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Background refresh failed: " + t.message)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlocklistWorker"
        private const val WORK_NAME = "blocklist_refresh"
        private const val INTERVAL_HOURS = 12L

        /**
         * Enqueue the periodic refresh job. Safe to call on every app
         * start: KEEP policy means an already-scheduled job is left
         * alone, so we don't reset the next-run timer on every launch.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistRefreshWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic refresh scheduled every " + INTERVAL_HOURS + "h")
        }
    }
}
