package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Persistent background data-sync worker promoted to a `dataSync` Foreground Service.
 * Ensures both top-right Catalog/NAV sync and full Universe History sync keep running smoothly
 * even when the user presses Back, switches tabs, locks the screen, or leaves the app.
 */
class UniverseSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = FundCompareRepository.getInstance(applicationContext)
        val mode = inputData.getString(KEY_MODE) ?: MODE_UNIVERSE
        val force = inputData.getBoolean(KEY_FORCE, false)
        val maxBatch = inputData.getInt(KEY_MAX_BATCH, Int.MAX_VALUE)

        if (mode == MODE_UNIVERSE && repo.isUniversePauseRequested()) {
            return Result.success()
        }

        val initialTitle = if (mode == MODE_CATALOG) {
            "Updating Direct Growth NAVs"
        } else {
            "Syncing Mutual Fund Histories"
        }
        try {
            setForeground(createForegroundInfo(title = initialTitle, text = "Connecting...", synced = 0, total = 0))
        } catch (_: Exception) {
            // Continue gracefully if OS disallows FGS promotion during background retry
        }

        if (mode == MODE_CATALOG) {
            val res = repo.awaitOrRunCatalogRefresh(force = force)
            return if (res.isSuccess || !repo.isAnySyncRunning()) {
                Result.success()
            } else {
                Result.retry()
            }
        }

        // Observe live progress while universe sync executes and update notification <= 1x/sec
        var lastNotifyTime = 0L
        return coroutineScope {
            val progressObserver = launch {
                repo.syncStatusFlow.collectLatest { status ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyTime >= 900L && status.isUniverseHistorySyncing) {
                        lastNotifyTime = now
                        val total = status.eligibleCatalogCount
                        val synced = status.historiesSyncedCount.coerceAtMost(total)
                        try {
                            setForeground(
                                createForegroundInfo(
                                    title = "Syncing Mutual Fund Histories",
                                    text = if (total > 0) "$synced / $total funds synced" else status.statusMessage,
                                    synced = synced,
                                    total = total
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            try {
                val completedCleanly = repo.executeUniverseHistorySyncPass(maxBatchCount = maxBatch)
                if (completedCleanly || repo.isUniversePauseRequested()) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } finally {
                progressObserver.cancel()
            }
        }
    }

    private fun createForegroundInfo(
        title: String,
        text: String,
        synced: Int,
        total: Int
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)

        if (total > 0) {
            builder.setProgress(total, synced.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification: Notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mf_compare_data_sync"
        private const val NOTIFICATION_ID = 4201
        private const val WORK_NAME_UNIVERSE = "mf_compare_universe_history_sync"
        private const val WORK_NAME_CATALOG = "mf_compare_catalog_nav_sync"

        const val KEY_MODE = "sync_mode"
        const val KEY_FORCE = "force_sync"
        const val KEY_MAX_BATCH = "max_batch"
        const val MODE_UNIVERSE = "universe"
        const val MODE_CATALOG = "catalog"

        fun enqueueUniverseSync(context: Context, maxBatchCount: Int = Int.MAX_VALUE): Boolean {
            return try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<UniverseSyncWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_UNIVERSE,
                            KEY_MAX_BATCH to maxBatchCount
                        )
                    )
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME_UNIVERSE,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun enqueueCatalogSync(context: Context, force: Boolean): Boolean {
            return try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<UniverseSyncWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_CATALOG,
                            KEY_FORCE to force
                        )
                    )
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME_CATALOG,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun cancelUniverseSync(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME_UNIVERSE)
            } catch (_: Throwable) {
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Mutual Fund Sync Progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while syncing Direct Growth mutual fund NAVs in the background."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
