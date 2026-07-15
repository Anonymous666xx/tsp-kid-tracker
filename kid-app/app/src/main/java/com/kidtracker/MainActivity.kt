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
    private val PREFS_NAME = "KidTrackerPrefs"
    private val PERMISSION_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedCode = prefs.getString("tracking_code", null)
        val savedApi = prefs.getString("api_base", null)

        if (savedCode != null && savedCode.length == 6 && savedApi != null && savedApi.isNotEmpty()) {
            fillCode(savedCode)
            binding.apiUrlInput.setText(savedApi)
            startTracking(savedCode)
            return
        }

        setupCodeInputs()
        binding.connectButton.isEnabled = false

        binding.apiUrlInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateConnectButton(listOf())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.connectButton.setOnClickListener {
            val code = getCodeFromInputs()
            val apiBase = binding.apiUrlInput.text.toString().trim().trimEnd('/')
            if (code.length == 6 && apiBase.isNotEmpty()) {
                prefs.edit().putString("tracking_code", code).putString("api_base", apiBase).apply()
                startTracking(code)
            }
        }

        checkPermissions()
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
                    updateConnectButton(editTexts)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
    }

    private fun updateConnectButton(editTexts: List<android.widget.EditText> = listOf()) {
        val code = if (editTexts.isNotEmpty()) {
            editTexts.joinToString("") { it.text.toString() }
        } else {
            getCodeFromInputs()
        }
        val apiBase = binding.apiUrlInput.text.toString().trim()
        binding.connectButton.isEnabled = code.length == 6 && apiBase.isNotEmpty()
    }

    private fun getCodeFromInputs(): String {
        return listOf(
            binding.code1, binding.code2, binding.code3,
            binding.code4, binding.code5, binding.code6
        ).joinToString("") { it.text.toString() }
    }

    private fun fillCode(code: String) {
        val chars = code.toCharArray()
        val editTexts = listOf(
            binding.code1, binding.code2, binding.code3,
            binding.code4, binding.code5, binding.code6
        )
        for (i in chars.indices) {
            if (i < editTexts.size) editTexts[i].setText(chars[i].toString())
        }
    }

    private fun startTracking(code: String) {
        val intent = Intent(this, LocationService::class.java).apply {
            putExtra("tracking_code", code)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.statusText.text = "Tracking Active"
        binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        binding.codeSection.alpha = 0.5f
        Toast.makeText(this, "Tracking started with code: $code", Toast.LENGTH_LONG).show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
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
                Toast.makeText(this, "Permissions granted. Tap Connect to start.", Toast.LENGTH_SHORT).show()
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), PERMISSION_REQUEST + 1)
                    }
                }
            } else {
                Toast.makeText(this, "Location permissions required for tracking", Toast.LENGTH_LONG).show()
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
