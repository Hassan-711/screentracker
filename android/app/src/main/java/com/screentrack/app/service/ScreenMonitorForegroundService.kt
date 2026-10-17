package com.screentrack.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screentrack.app.MainActivity
import com.screentrack.app.data.repository.SupabaseRepository
import com.screentrack.app.data.repository.UsageStatsRepository
import com.screentrack.app.worker.UsageSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that keeps broadcasting live screen time and active app
 * every 10 seconds even when the ScreenTrack UI is completely closed or swiped away from Recents.
 */
class ScreenMonitorForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private lateinit var usageRepo: UsageStatsRepository
    private lateinit var supabaseRepo: SupabaseRepository
    private var tickCount = 0

    override fun onCreate() {
        super.onCreate()
        usageRepo = UsageStatsRepository(this)
        supabaseRepo = SupabaseRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification("Live screen-time sharing is active")
        var startedSuccessfully = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    startedSuccessfully = true
                } catch (_: Throwable) {
                    try {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                        startedSuccessfully = true
                    } catch (_: Throwable) {
                        try {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                            startedSuccessfully = true
                        } catch (_: Throwable) {
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    startedSuccessfully = true
                } catch (_: Throwable) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                        startedSuccessfully = true
                    } catch (_: Throwable) {
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
                startedSuccessfully = true
            }
        } catch (_: Throwable) {
        }

        if (startedSuccessfully) {
            try {
                supabaseRepo.setForegroundMonitorEnabled(true)
                startActivePolling()
            } catch (_: Throwable) {
            }
            return START_STICKY
        } else {
            try {
                UsageSyncWorker.triggerImmediateSync(this)
            } catch (_: Throwable) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
    }

    /**
     * Called when Person A swipes ScreenTrack away from the Recent Apps list.
     * Triggers WorkManager immediate sync cleanly without background start violations.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (supabaseRepo.isForegroundMonitorEnabled()) {
                UsageSyncWorker.triggerImmediateSync(applicationContext)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startActivePolling() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val summary = usageRepo.getTodayScreenTimeSummary()
                    // Sync 7-day history on initial start and every 30 ticks (~5 minutes)
                    val weekly = if (tickCount % 30 == 0) {
                        usageRepo.getWeeklyAnalytics()
                    } else {
                        emptyList()
                    }
                    tickCount++

                    if (supabaseRepo.isConfigured && supabaseRepo.getCurrentSession() != null) {
                        supabaseRepo.syncScreenTimeSummary(summary, weekly)
                    }

                    val statusText = if (summary.isCurrentSessionActive && !summary.currentForegroundApp.isNullOrBlank()) {
                        "Active: ${summary.currentForegroundApp} (${summary.formattedCurrentSession}) • Today: ${summary.formattedTotalScreenTime}"
                    } else {
                        "Screen idle • Today's total: ${summary.formattedTotalScreenTime}"
                    }
                    updateNotification(statusText)
                } catch (_: Throwable) {
                }
                delay(10_000L) // Fast 10-second live updates even when app is closed
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ScreenTrack Live Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps live screen-time sharing active when the app is closed"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenTrack Live Sharing")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "screentrack_monitor_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            try {
                SupabaseRepository(context).setForegroundMonitorEnabled(true)
                val intent = Intent(context, ScreenMonitorForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                UsageSyncWorker.triggerImmediateSync(context)
            }
        }

        fun stop(context: Context) {
            try {
                SupabaseRepository(context).setForegroundMonitorEnabled(false)
                val intent = Intent(context, ScreenMonitorForegroundService::class.java)
                context.stopService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
