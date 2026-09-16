package com.autoclicker.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Lightweight foreground service — only keeps the process alive and shows a notification.
 * The actual floating panel is drawn by ClickAccessibilityService (TYPE_ACCESSIBILITY_OVERLAY)
 * so it survives inside games like Roblox.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.autoclicker.floating.START_OVERLAY"
        const val ACTION_STOP = "com.autoclicker.floating.STOP_OVERLAY"
        const val ACTION_UPDATE_UI = "com.autoclicker.floating.UPDATE_UI"

        @Volatile
        var isOverlayShowing = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ClickAccessibilityService.hidePanel()
                isOverlayShowing = false
                stopSelf()
            }
            else -> {
                // Ask AccessibilityService to show the panel (TYPE_ACCESSIBILITY_OVERLAY)
                ClickAccessibilityService.showPanel()
                isOverlayShowing = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Clicker Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the auto clicker alive"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
