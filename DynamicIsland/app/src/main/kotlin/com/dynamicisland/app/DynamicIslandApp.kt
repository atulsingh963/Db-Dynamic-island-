package com.dynamicisland.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application singleton. Sets up the persistent notification channel
 * used by the foreground service so the OS never kills the overlay.
 */
class DynamicIslandApp : Application() {

    companion object {
        const val CHANNEL_ID = "dynamic_island_channel"
        const val CHANNEL_NAME = "Dynamic Island"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN  // silent – no sound/vibration
            ).apply {
                description = "Keeps the Dynamic Island overlay alive"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
