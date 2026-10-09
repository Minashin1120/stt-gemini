package com.minashin1120.voxcribe.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.MainActivity
import com.minashin1120.voxcribe.R
import com.minashin1120.voxcribe.VoxcribeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新APKの取得中にプロセスを維持し、通知に進捗を表示する。 */
class UpdateDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tag = intent?.getStringExtra(EXTRA_TAG)
        val version = intent?.getStringExtra(EXTRA_VERSION)
        val url = intent?.getStringExtra(EXTRA_URL)
        val size = intent?.getLongExtra(EXTRA_SIZE, -1L) ?: -1L
        if (tag.isNullOrBlank() || version.isNullOrBlank() || url.isNullOrBlank() || size < 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val target = UpdateInfo(tag, version, intent?.getStringExtra(EXTRA_NOTES).orEmpty(), url, size)
        ensureChannel()
        startInForeground(buildNotification(0))
        serviceScope.launch {
            try {
                val file = VoxcribeApp.instance.updates.download(target) { value ->
                    val percent = (value * 100).toInt().coerceIn(0, 100)
                    mainHandler.post {
                        VoxcribeApp.instance.updates.updateProgress(value)
                        if (percent != lastNotifiedPercent) {
                            lastNotifiedPercent = percent
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(percent))
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    VoxcribeApp.instance.updates.updateComplete(file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    VoxcribeApp.instance.updates.updateFailed()
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "アプリの更新", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(percent: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voxcribe)
            .setContentTitle("Voxcribeを更新中")
            .setContentText("APKをダウンロード中 $percent%")
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voxcribe_update"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_NOTES = "notes"

        fun start(context: Context, target: UpdateInfo) {
            val intent = Intent(context, UpdateDownloadService::class.java)
                .putExtra(EXTRA_TAG, target.tag)
                .putExtra(EXTRA_VERSION, target.version)
                .putExtra(EXTRA_URL, target.downloadUrl)
                .putExtra(EXTRA_SIZE, target.sizeBytes)
                .putExtra(EXTRA_NOTES, target.notes)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
