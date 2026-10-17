package com.screentrack.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screentrack.app.data.repository.PermissionState
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.data.repository.UsageStatsRepository
import com.screentrack.app.worker.UsageSyncWorker

/**
 * Automatically restarts Live Broadcast Mode and Periodic Sync when Person A's phone
 * reboots or updates, without requiring Person A to open the app manually.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                val supabaseRepo = SupabaseRepository(context)
                val usageRepo = UsageStatsRepository(context)

                // Always re-schedule WorkManager periodic sync
                UsageSyncWorker.schedulePeriodicSync(context, supabaseRepo.getSyncIntervalMinutes().toLong())
                UsageSyncWorker.triggerImmediateSync(context)

                // If Live 10-Second Broadcast Mode was enabled, restart the foreground service
                if (
                    supabaseRepo.isForegroundMonitorEnabled() &&
                    usageRepo.checkPermissionState() is PermissionState.Granted
                ) {
                    ScreenMonitorForegroundService.start(context)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
