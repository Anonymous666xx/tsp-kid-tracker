package com.kidtracker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var polling = false
    private var trackingCode = ""
    private var deviceId = ""

    companion object {
        private const val CHANNEL_ID = "ScreenshotChannel"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "ScreenshotService"
        private var API_BASE: String = "https://tsp.omaromartest12.workers.dev"
        var projectionData: Intent? = null
        var resultCode: Int = Activity.RESULT_CANCELED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trackingCode = intent?.getStringExtra("tracking_code") ?: ""
        deviceId = intent?.getStringExtra("device_id") ?: "default"

        if (trackingCode.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification("Screenshot monitoring active")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startPolling()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors for screenshot requests"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TSP Screenshot")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPolling() {
        polling = true
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!polling) return
                checkForScreenshotRequest()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(pollRunnable)
    }

    private fun checkForScreenshotRequest() {
        if (executor.isShutdown) return
        executor.execute {
            try {
                val url = URL("$API_BASE/api/check-screenshot?code=$trackingCode&device_id=$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                if (json.optBoolean("pending", false)) {
                    val requestId = json.getString("id")
                    handler.post { takeScreenshot(requestId) }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check screenshot request", e)
            }
        }
    }

    private fun takeScreenshot(requestId: String) {
        val projection = projectionData
        if (projection == null) {
            Log.w(TAG, "No MediaProjection available")
            return
        }

        try {
            val mgr = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            mgr.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, projection)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Screenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            handler.postDelayed({
                try {
                    val image: Image? = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle()

                        val stream = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        cropped.recycle()
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"

                        uploadScreenshot(requestId, dataUrl)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to capture screenshot", e)
                }
                cleanup()
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot", e)
            cleanup()
        }
    }

    private fun uploadScreenshot(requestId: String, screenshot: String) {
        executor.execute {
            try {
                val url = URL("$API_BASE/api/upload-screenshot")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("id", requestId)
                    put("code", trackingCode)
                    put("device_id", deviceId)
                    put("screenshot", screenshot)
                }
                conn.outputStream.write(json.toString().toByteArray())
                val response = conn.responseCode
                Log.d(TAG, "Screenshot uploaded (HTTP $response)")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload screenshot", e)
            }
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        cleanup()
        executor.shutdownNow()
        super.onDestroy()
    }
}
