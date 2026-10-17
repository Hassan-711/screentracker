package com.screentrack.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screentrack.app.data.repository.PermissionState
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.data.repository.UsageStatsRepository
import java.util.concurrent.TimeUnit

class UsageSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val usageRepo = UsageStatsRepository(applicationContext)
            val supabaseRepo = SupabaseRepository(applicationContext)

            if (usageRepo.checkPermissionState() !is PermissionState.Granted) {
                return Result.success()
            }

            if (!supabaseRepo.isConfigured || supabaseRepo.getCurrentSession() == null) {
                return Result.success()
            }

            val summary = usageRepo.getTodayScreenTimeSummary()
            val weeklyHistory = usageRepo.getWeeklyAnalytics()
            val syncResult = supabaseRepo.syncScreenTimeSummary(summary, weeklyHistory)

            if (syncResult.isSuccess) {
                Result.success()
            } else if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Throwable) {
            Result.success()
        }
    }

    companion object {
        const val PERIODIC_WORK_TAG = "screentrack_periodic_sync"
        const val IMMEDIATE_WORK_TAG = "screentrack_immediate_sync"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicRequest = PeriodicWorkRequestBuilder<UsageSyncWorker>(
                    intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            } catch (_: Throwable) {
            }
        }

        fun triggerImmediateSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val oneTimeRequest = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_TAG,
                    ExistingWorkPolicy.REPLACE,
                    oneTimeRequest
                )
            } catch (_: Throwable) {
            }
        }
    }
}
