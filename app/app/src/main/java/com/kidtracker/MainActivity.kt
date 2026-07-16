package com.kidtracker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kidtracker.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PREFS_NAME = "TrackerIDPrefs"
    private val PERMISSION_REQUEST = 1001
    private val API_BASE = "https://tsp.omaromartest12.workers.dev"
    private val handler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pollingPair = false
    private var dialogShowing = false
    private val processedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        binding.stopButton.setOnClickListener {
            pollingPair = false
            dialogShowing = false
            processedIds.clear()
            handler.removeCallbacksAndMessages(null)
            try { executor.shutdownNow() } catch (_: Exception) {}
            executor = Executors.newSingleThreadExecutor()
            prefs.edit().remove("tracking_code").apply()
            stopService(Intent(this, LocationService::class.java))
            showSetupUI()
        }

        setupCodeInputs()
        binding.connectButton.isEnabled = false
        binding.connectButton.setOnClickListener {
            val code = getCodeFromInputs()
            if (code.length == 6) {
                checkAllPermissionsThenStart(code)
            }
        }

        val savedCode = prefs.getString("tracking_code", null)

        if (savedCode != null && savedCode.length == 6) {
            ensureCallLogPermission()
            startTracking(savedCode)
            return
        }

        checkPermissions()
    }

    private fun checkAllPermissionsThenStart(code: String) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CALL_LOG
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
            pendingStartCode = code
        } else {
            startTracking(code)
        }
    }

    private var pendingStartCode: String? = null

    private fun ensureCallLogPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST)
        }
    }

    private fun showSetupUI() {
        binding.setupSection.visibility = View.VISIBLE
        binding.activeSection.visibility = View.GONE
        binding.code1.setText("")
        binding.code2.setText("")
        binding.code3.setText("")
        binding.code4.setText("")
        binding.code5.setText("")
        binding.code6.setText("")
        binding.connectButton.isEnabled = false
        binding.code1.requestFocus()
    }

    private fun setupCodeInputs() {
        val editTexts = listOf(
            binding.code1, binding.code2, binding.code3,
            binding.code4, binding.code5, binding.code6
        )
        for (i in editTexts.indices) {
            editTexts[i].addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < 5) editTexts[i + 1].requestFocus()
                    val code = editTexts.joinToString("") { it.text.toString() }
                    binding.connectButton.isEnabled = code.length == 6
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
    }

    private fun getCodeFromInputs(): String {
        return listOf(
            binding.code1, binding.code2, binding.code3,
            binding.code4, binding.code5, binding.code6
        ).joinToString("") { it.text.toString() }
    }

    private fun startTracking(code: String) {
        try {
            val intent = Intent(this, LocationService::class.java).apply {
                putExtra("tracking_code", code)
            }
            ContextCompat.startForegroundService(this, intent)
            binding.setupSection.visibility = View.GONE
            binding.activeSection.visibility = View.VISIBLE
            binding.activeCode.text = "Code: $code"
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("tracking_code", code).apply()
            startPairPolling(code)
        } catch (e: Exception) {
            Log.e("TrackerID", "Failed to start tracking", e)
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPairPolling(code: String) {
        pollingPair = true
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!pollingPair) return
                fetchPendingPairs(code)
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(pollRunnable)
    }

    private fun fetchPendingPairs(code: String) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                val url = URL("$API_BASE/api/pending-pairs?code=$code")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                val response = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(response)
                if (arr.length() > 0) {
                    val request = arr.getJSONObject(0)
                    val id = request.getString("id")
                    val deviceInfo = request.optString("device_info", "Unknown device")
                    if (!processedIds.contains(id) && !dialogShowing) {
                        processedIds.add(id)
                        handler.post { showPairDialog(id, deviceInfo, code) }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("TrackerID", "Failed to fetch pending pairs", e)
            }
        }
    }

    private fun showPairDialog(id: String, deviceInfo: String, code: String) {
        dialogShowing = true
        try {
            val dialog = AlertDialog.Builder(this).create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setGravity(Gravity.CENTER)

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 50, 60, 40)
                setBackgroundColor(Color.parseColor("#0d1117"))
            }

            val title = TextView(this).apply {
                text = "Connection Request"
                setTextColor(Color.parseColor("#00e5ff"))
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }

            val device = TextView(this).apply {
                text = "A device wants to track this phone:\n\n$deviceInfo\n\nAccept?"
                setTextColor(Color.parseColor("#aabbbb"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }

            val acceptBtn = Button(this).apply {
                text = "ACCEPT"
                setTextColor(Color.parseColor("#060612"))
                setBackgroundColor(Color.parseColor("#00e5ff"))
                setOnClickListener {
                    dialog.dismiss()
                    dialogShowing = false
                    respondToPair(id, "accept", code)
                }
            }

            val declineBtn = Button(this).apply {
                text = "DECLINE"
                setTextColor(Color.parseColor("#ff5252"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    dialog.dismiss()
                    dialogShowing = false
                    respondToPair(id, "decline", code)
                }
            }

            layout.addView(title)
            layout.addView(device)
            layout.addView(acceptBtn)
            layout.addView(declineBtn)
            dialog.setView(layout)
            dialog.setCancelable(false)
            dialog.setOnDismissListener { dialogShowing = false }
            dialog.show()
        } catch (e: Exception) {
            Log.e("TrackerID", "Failed to show pair dialog", e)
            dialogShowing = false
        }
    }

    private fun respondToPair(id: String, action: String, code: String) {
        Thread {
            try {
                val url = URL("$API_BASE/api/pair-$action")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                val json = JSONObject().apply { put("id", id); put("code", code) }
                conn.outputStream.write(json.toString().toByteArray())
                val responseCode = conn.responseCode
                Log.d("TrackerID", "Pair $action response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("TrackerID", "Pair $action failed", e)
            }
        }.start()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CALL_LOG
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted && android.os.Build.VERSION.SDK_INT >= 29) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), PERMISSION_REQUEST + 1)
                    return
                }
            }
            pendingStartCode?.let { code ->
                pendingStartCode = null
                startTracking(code)
            }
            if (!allGranted) {
                Toast.makeText(this, "All permissions required for tracking and call logs", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == PERMISSION_REQUEST + 1) {
            pendingStartCode?.let { code ->
                pendingStartCode = null
                startTracking(code)
            }
        }
    }

    override fun onDestroy() {
        pollingPair = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
