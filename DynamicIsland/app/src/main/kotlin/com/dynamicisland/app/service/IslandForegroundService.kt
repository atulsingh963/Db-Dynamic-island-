package com.dynamicisland.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.dynamicisland.app.DynamicIslandApp
import com.dynamicisland.app.R
import com.dynamicisland.app.ui.MainActivity

/**
 * IslandForegroundService
 *
 * A minimal foreground service that keeps the overlay process alive
 * and prevents the OS from killing it during aggressive battery management.
 *
 * It displays a persistent, minimal notification (IMPORTANCE_MIN) so that
 * the user is aware the service is running, and provides a tap-to-configure
 * action that opens [MainActivity].
 *
 * Uses [LifecycleService] so Lifecycle-aware components can observe it.
 */
class IslandForegroundService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY   // restart automatically if killed
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DynamicIslandApp.CHANNEL_ID)
            .setContentTitle("Dynamic Island")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_island)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
