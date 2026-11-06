package com.vehiclegate.anpr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vehiclegate.anpr.AnprApp
import com.vehiclegate.anpr.R
import com.vehiclegate.anpr.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps vehicle detection running in the background – app minimised, another app open, or the
 * screen off – until the user taps Exit in the app or Stop in the notification.
 * Restarted by Android if the process is killed (START_STICKY).
 */
class AnprService : Service() {

    companion object {
        private const val CHANNEL = "scanning"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.vehiclegate.anpr.action.STOP"

        /** Start, or apply changed cameras/settings to the running service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AnprService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnprService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting cameras…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        // Keep CPU and Wi-Fi awake while scanning with the screen off.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleGate:scanning").apply {
                setReferenceCounted(false); acquire()
            }
        try {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VehicleGate:rtsp").apply {
                    setReferenceCounted(false); acquire()
                }
        } catch (_: Exception) { }
        AnprEngine.onSummaryChanged = { updateNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AnprEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        AnprEngine.startOrReload(application as AnprApp)
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        AnprEngine.onSummaryChanged = null
        AnprEngine.stop()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun summary(): String {
        val n = AnprEngine.cameraCount
        if (n == 0) return "No cameras configured – open the app to add cameras"
        val last = AnprEngine.lastVehicle?.let { " · Last: ${it.plateFormatted} at ${timeFmt.format(Date(it.entryTime))}" } ?: ""
        return "Scanning $n camera${if (n == 1) "" else "s"}$last"
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(summary()))
        } catch (_: Exception) { }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AnprService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("Vehicle Gate ANPR is running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Background scanning", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shown while cameras are being scanned for number plates"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
