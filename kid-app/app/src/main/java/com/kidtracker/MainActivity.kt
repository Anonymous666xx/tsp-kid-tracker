package com.kidtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kidtracker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PREFS_NAME = "TrackerIDPrefs"
    private val PERMISSION_REQUEST = 1001
    private val API_BASE = "https://tsp.omaromartest12.workers.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedCode = prefs.getString("tracking_code", null)

        if (savedCode != null && savedCode.length == 6) {
            startTracking(savedCode)
            return
        }

        setupCodeInputs()
        binding.connectButton.isEnabled = false
        binding.connectButton.setOnClickListener {
            val code = getCodeFromInputs()
            if (code.length == 6) {
                prefs.edit().putString("tracking_code", code).apply()
                startTracking(code)
            }
        }

        binding.stopButton.setOnClickListener {
            prefs.edit().remove("tracking_code").apply()
            stopService(Intent(this, LocationService::class.java))
            showSetupUI()
        }

        checkPermissions()
    }

    private fun showSetupUI() {
        binding.setupSection.alpha = 1f
        binding.setupSection.isEnabled = true
        binding.setupSection.visibility = android.view.View.VISIBLE
        binding.activeSection.visibility = android.view.View.GONE
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
                    if (s?.length == 1 && i < 5) {
                        editTexts[i + 1].requestFocus()
                    }
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
        val intent = Intent(this, LocationService::class.java).apply {
            putExtra("tracking_code", code)
        }
        ContextCompat.startForegroundService(this, intent)

        binding.setupSection.visibility = android.view.View.GONE
        binding.activeSection.visibility = android.view.View.VISIBLE
        binding.activeCode.text = "Code: $code"
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show()
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), PERMISSION_REQUEST + 1)
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getString("tracking_code", null) == null) {
            stopService(Intent(this, LocationService::class.java))
        }
    }
}
