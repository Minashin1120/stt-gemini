package com.minashin1120.voxcribe.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.MainActivity
import com.minashin1120.voxcribe.R

/**
 * 録音中・AI処理中にプロセスを維持するフォアグラウンドサービス。
 * 画面OFFやバックグラウンドでも録音と文字起こしを継続する（Web版のタスク継続に相当）。
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
        val processing = intent?.getBooleanExtra(EXTRA_PROCESSING, false) ?: false
        if (!recording && !processing) {
            stopSelfCompletely()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val text = when {
            recording -> "録音中..."
            else -> "文字起こし処理中..."
        }
        val notif = buildNotification(text)
        var type = 0
        if (recording) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (processing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            startForeground(NOTIF_ID, notif, type)
        } catch (_: Exception) {
            try { startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (_: Exception) {}
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voxcribe:work").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfCompletely() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voxcribe)
            .setContentTitle("Voxcribe")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL = "voxcribe_work"
        private const val NOTIF_ID = 1001
        private const val EXTRA_RECORDING = "recording"
        private const val EXTRA_PROCESSING = "processing"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "録音・文字起こし", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun update(ctx: Context, recording: Boolean, processing: Boolean) {
            val i = Intent(ctx, WorkService::class.java)
                .putExtra(EXTRA_RECORDING, recording)
                .putExtra(EXTRA_PROCESSING, processing)
            try {
                if (recording || processing) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
            } catch (_: Exception) {
            }
        }
    }
}
