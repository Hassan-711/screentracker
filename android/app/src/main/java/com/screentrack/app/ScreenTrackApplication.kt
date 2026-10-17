package com.screentrack.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.screentrack.app.worker.UsageSyncWorker

class ScreenTrackApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            com.screentrack.app.util.AppUpdateManager.cleanupDownloadedApks(this)
            createNotificationChannels()
            scheduleBackgroundSync()
        } catch (_: Throwable) {
            // Prevent any OEM WorkManager or NotificationManager quirk from crashing app startup
        }
    }

    private fun scheduleBackgroundSync() {
        UsageSyncWorker.schedulePeriodicSync(this, 15)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC_ID,
                getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_sync_description)
            }

            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_monitor_description)
            }

            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(monitorChannel)
        }
    }

    companion object {
        const val CHANNEL_SYNC_ID = "screentrack_sync_channel"
        const val CHANNEL_MONITOR_ID = "screentrack_monitor_channel"
    }
}
