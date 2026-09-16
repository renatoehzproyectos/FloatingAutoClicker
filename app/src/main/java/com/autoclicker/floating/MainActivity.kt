package com.autoclicker.floating

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var btnConfigure: MaterialButton
    private lateinit var statusLabel: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var overlayDot: View
    private lateinit var a11yDot: View
    private lateinit var overlayPermissionText: TextView
    private lateinit var a11yPermissionText: TextView
    private lateinit var permissionHint: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatusUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConfigure = findViewById(R.id.btnConfigure)
        statusLabel = findViewById(R.id.statusLabel)
        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.statusDot)
        overlayDot = findViewById(R.id.overlayDot)
        a11yDot = findViewById(R.id.a11yDot)
        overlayPermissionText = findViewById(R.id.overlayPermissionText)
        a11yPermissionText = findViewById(R.id.a11yPermissionText)
        permissionHint = findViewById(R.id.permissionHint)

        btnConfigure.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                requestAccessibility()
                return@setOnClickListener
            }
            openFloatingPanel()
        }

        findViewById<View>(R.id.overlayPermissionChip).setOnClickListener {
            if (!hasOverlayPermission()) requestOverlayPermission()
        }
        findViewById<View>(R.id.a11yPermissionChip).setOnClickListener {
            if (!isAccessibilityEnabled()) requestAccessibility()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
        updatePermissionUI()
        val filter = IntentFilter(OverlayService.ACTION_UPDATE_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun openFloatingPanel() {
        startOverlayService()
        ClickAccessibilityService.showPanel()
        Toast.makeText(
            this,
            "Panel opened. Use sliders, + / − for points, then Play.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateStatusUI() {
        if (ClickAccessibilityService.userWantsClicking && ClickAccessibilityService.isRunning) {
            statusLabel.text = "RUNNING"
            statusLabel.setTextColor(ContextCompat.getColor(this, R.color.success))
            statusPill.setBackgroundResource(R.drawable.status_pill_running)
            statusDot.setBackgroundResource(R.drawable.status_dot_running)
        } else {
            statusLabel.text = "STOPPED"
            statusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            statusPill.setBackgroundResource(R.drawable.status_pill_stopped)
            statusDot.setBackgroundResource(R.drawable.status_dot_stopped)
        }
    }

    private fun updatePermissionUI() {
        val overlayOk = hasOverlayPermission()
        val a11yOk = isAccessibilityEnabled()

        if (overlayOk) {
            overlayDot.setBackgroundResource(R.drawable.status_dot_ok)
            overlayPermissionText.text = "Overlay ✓"
        } else {
            overlayDot.setBackgroundResource(R.drawable.status_dot_stopped)
            overlayPermissionText.text = "Overlay"
        }

        if (a11yOk) {
            a11yDot.setBackgroundResource(R.drawable.status_dot_ok)
            a11yPermissionText.text = "Accessibility ✓"
        } else {
            a11yDot.setBackgroundResource(R.drawable.status_dot_stopped)
            a11yPermissionText.text = "Accessibility"
        }

        when {
            overlayOk && a11yOk -> {
                permissionHint.text = "All set. Open the floating panel to control clicks."
                permissionHint.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            else -> {
                permissionHint.text = "Both permissions are required. Tap a chip to grant."
                permissionHint.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }
    }

    private fun startOverlayService() {
        try {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                    )
                }
            }
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start OverlayService", e)
            Toast.makeText(this, "Could not start overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Enable “Display over other apps”", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun requestAccessibility() {
        Toast.makeText(this, "Enable Floating Auto Clicker in Accessibility", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
