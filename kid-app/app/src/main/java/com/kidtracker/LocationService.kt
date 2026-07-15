package com.kidtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.util.Log

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var trackingCode: String = ""
    private val executor = Executors.newSingleThreadExecutor()
    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0

    companion object {
        private const val CHANNEL_ID = "KidTrackerChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "KidTracker"
        private var API_BASE: String = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trackingCode = intent?.getStringExtra("tracking_code") ?: ""
        if (trackingCode.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        API_BASE = getSharedPreferences("KidTrackerPrefs", MODE_PRIVATE)
            .getString("api_base", null) ?: ""

        if (API_BASE.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kid Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracking your kid's location"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kid Tracker Active")
            .setContentText("Tracking location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val now = System.currentTimeMillis()
                    val timeSinceLastSend = now - lastSentTime
                    val distFromLast = lastSentLocation?.distanceTo(location) ?: Float.MAX_VALUE

                    if (timeSinceLastSend >= 1000 || distFromLast > 5) {
                        sendLocation(location)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            stopSelf()
        }
    }

    private fun sendLocation(location: Location) {
        val code = trackingCode
        val latitude = location.latitude
        val longitude = location.longitude
        val accuracy = location.accuracy

        val batteryLevel = getBatteryLevel()

        lastSentLocation = location
        lastSentTime = System.currentTimeMillis()

        executor.execute {
            try {
                val url = URL("$API_BASE/api/update-location")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.doOutput = true

                val json = """{"code":"$code","latitude":$latitude,"longitude":$longitude,"accuracy":$accuracy,"battery":$batteryLevel}"""
                conn.outputStream.write(json.toByteArray())

                val response = conn.responseCode
                Log.d(TAG, "Location sent: $latitude, $longitude (HTTP $response)")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send location", e)
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        executor.shutdown()
        super.onDestroy()
    }
}
