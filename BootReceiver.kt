package com.example.echonum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that handles starting the VPN service on boot
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed intent received")

            // Check for auto-start preference
            val prefs = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean("vpn_on_boot", false)

            if (startOnBoot) {
                Log.d("BootReceiver", "Auto-start is enabled, starting VPN service")
                try {
                    val serviceIntent = Intent(context, ForegroundVpnService::class.java)

                    // On Android 8.0+ we need to start as a foreground service
                    context.startForegroundService(serviceIntent)

                    Log.d("BootReceiver", "VPN service started successfully")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start VPN service", e)
                }
            } else {
                Log.d("BootReceiver", "Auto-start is disabled")
            }
        }
    }
}