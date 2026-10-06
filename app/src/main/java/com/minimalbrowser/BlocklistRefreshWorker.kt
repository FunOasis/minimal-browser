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
 * Periodic background refresh of the two warehouses:
 *
 *   - BlocklistStore   (host-based ad blocking, 24h per-list freshness)
 *   - CosmeticStore    (EasyList cosmetic rules, 72h per-list freshness)
 *
 * Both are refreshed on the same 12h tick. The stores' own freshness
 * checks skip lists that are still young, so the second daily run is
 * usually a timestamp-only no-op.
 *
 * Why WorkManager and not a coroutine in MinimalBrowserApp:
 *  - a coroutine dies when the process is killed
 *  - Doze mode defers or drops bare coroutines
 *  - WorkManager survives process death, wakes on Doze exit, and
 *    retries with exponential backoff on transient failures
 */
class BlocklistRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            refreshHosts()
            refreshCosmetics()
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Background refresh failed: " + t.message)
            Result.retry()
        }
    }

    private suspend fun refreshHosts() {
        try {
            val store = BlocklistStore.get(applicationContext)
            val summary = store.refreshAll(force = false)
            Log.i(TAG, "Host refresh: refreshed=" + summary.refreshed +
                " failed=" + summary.failed +
                " skipped=" + summary.skipped +
                " totalHosts=" + summary.totalHosts)

            if (summary.refreshed > 0) {
                AdBlocker.get(applicationContext).reloadCustomRules()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Host refresh failed: " + t.message)
        }
    }

    private suspend fun refreshCosmetics() {
        try {
            val store = CosmeticStore.get(applicationContext)
            val refreshed = store.refreshAll(force = false)
            Log.i(TAG, "Cosmetic refresh: updated=" + refreshed)

            if (refreshed > 0) {
                val raw = store.loadAllRaw()
                if (raw.isNotEmpty()) CosmeticFilter.updateFrom(raw)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cosmetic refresh failed: " + t.message)
        }
    }

    companion object {
        private const val TAG = "BlocklistWorker"
        private const val WORK_NAME = "blocklist_refresh"
        private const val INTERVAL_HOURS = 12L

        /**
         * Enqueue the periodic refresh job. Safe to call on every app
         * start: KEEP policy leaves an already-scheduled job alone, so
         * we do not reset the next-run timer on every launch.
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
