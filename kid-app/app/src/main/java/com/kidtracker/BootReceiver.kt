package com.kidtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("TrackerIDPrefs", Context.MODE_PRIVATE)
        val code = prefs.getString("tracking_code", null)
        val apiBase = prefs.getString("api_base", null)

        if (code != null && code.length == 6 && apiBase != null && apiBase.isNotEmpty()) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                val serviceIntent = Intent(context, LocationService::class.java).apply {
                    putExtra("tracking_code", code)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
