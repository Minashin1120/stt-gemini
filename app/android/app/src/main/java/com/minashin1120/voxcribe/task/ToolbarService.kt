package com.minashin1120.voxcribe.task

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.VoxcribeApp

/** 通知から開始した録音・文字起こしの間だけ動作する。待機時は通常の継続通知。 */
class ToolbarService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val toolbar = VoxcribeApp.instance.toolbar
        if (intent?.action == RecordingToolbar.START && !toolbar.busy) {
            // getForegroundServiceから届くため、開始できない場合も先にforeground登録する。
            // 通知のタップはバックグラウンドでのmicrophoneサービス開始の例外に該当する。
            try {
                startForeground(RecordingToolbar.ID, toolbar.notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "録音を開始できません。アプリの設定でマイク権限を確認してください", android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            if (!toolbar.start()) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            RecordingToolbar.STOP -> toolbar.stop()
            RecordingToolbar.CANCEL -> toolbar.cancel()
        }
        if (!toolbar.busy) { stopSelf(); return START_NOT_STICKY }
        try {
            val type = if (toolbar.recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(RecordingToolbar.ID, toolbar.notification(), type)
            if (toolbar.recording) toolbar.beginCapture()
        } catch (_: Exception) {
            toolbar.cancel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_DETACH)
        VoxcribeApp.instance.toolbar.serviceDestroyed()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        VoxcribeApp.instance.toolbar.cancel()
        stopSelf()
    }

    companion object {
        fun update(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ToolbarService::class.java))
        }
    }
}
