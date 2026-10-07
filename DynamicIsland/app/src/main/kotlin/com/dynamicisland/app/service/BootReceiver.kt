package com.dynamicisland.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * BootReceiver
 *
 * Handles two events:
 * 1. [BOOT_COMPLETED] / [MY_PACKAGE_REPLACED] → restart the foreground service
 *    so the overlay comes back after a device reboot or app update.
 * 2. [ACTION_POWER_CONNECTED] / [ACTION_BATTERY_CHANGED] → detect charging state
 *    and forward battery percentage to [IslandNotificationService].
 *
 * A companion [BatteryReceiver] is registered dynamically by [IslandForegroundService]
 * to catch real-time battery events.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-start foreground service after boot / update
                val svc = Intent(context, IslandForegroundService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }

            Intent.ACTION_POWER_CONNECTED -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                IslandNotificationService.instance?.onChargingChanged(pct, isCharging = true)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                IslandNotificationService.instance?.onChargingChanged(0, isCharging = false)
            }
        }
    }
}
