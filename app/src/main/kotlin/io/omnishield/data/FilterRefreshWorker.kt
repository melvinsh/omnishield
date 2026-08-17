package io.omnishield.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps the downloaded blocklists current.
 *
 * Previously the lists were only fetched while a tunnel was starting, which meant a device
 * left connected for a week ran on week-old rules. Refreshing on a schedule decouples list
 * freshness from tunnel lifecycle.
 *
 * The freshly downloaded lists are written to the cache here but are *not* pushed into a
 * running core: swapping ~430k rules out from under the packet thread mid-session is not worth
 * the risk for rules that change daily. They are picked up on the next tunnel start.
 */
class FilterRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = FilterRepository(applicationContext)
        return runCatching {
            val dns = repo.refresh()
            val content = repo.refreshContentRules()
            Log.i(TAG, "refreshed ${dns.bodies.size} DNS and ${content.bodies.size} content lists")
            val failed = dns.failed + content.failed
            if (failed.isNotEmpty()) Log.w(TAG, "could not reach: ${failed.joinToString()}")
            // A partial refresh is still progress; retry only when nothing came back at all, so
            // one unreachable source does not drag the whole job into its backoff schedule.
            if (dns.bodies.isEmpty() && content.bodies.isEmpty()) Result.retry() else Result.success()
        }.getOrElse {
            Log.w(TAG, "filter refresh failed: ${it.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FilterRefresh"
        private const val WORK_NAME = "omnishield.filter-refresh"

        fun schedule(context: Context) {
            // A six-hour flex window on a daily job lets the scheduler batch this with other
            // wakeups instead of pinning it to a fixed moment.
            val request = PeriodicWorkRequestBuilder<FilterRefreshWorker>(
                1, TimeUnit.DAYS,
                6, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered only: the lists total roughly 13 MB, which is not
                        // something to pull over a metered connection unasked.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        // Nothing depends on these lists being fresh at any particular moment,
                        // so waiting for the device to be idle costs the user nothing and
                        // keeps a 13 MB download off the interactive path entirely.
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                // Default backoff starts at 30 s, which for a daily job means retrying a large
                // download far more eagerly than it deserves.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
