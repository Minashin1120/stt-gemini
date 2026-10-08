package com.minashin1120.voxcribe.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.minashin1120.voxcribe.MainActivity
import com.minashin1120.voxcribe.R
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.AiEvent
import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.audio.Finalize
import com.minashin1120.voxcribe.audio.MicProbe
import com.minashin1120.voxcribe.audio.MicSettings
import com.minashin1120.voxcribe.ui.workspace.StatusView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 通知専用の録音。履歴・保存音声・結果UIは更新しない。録音機器のみworkspaceと共有する。 */
class RecordingToolbar(private val app: VoxcribeApp) {
    var recording = false
        private set
    var processing = false
        private set
    val busy get() = recording || processing
    private var mic: MicSettings? = null
    private var request: AiRequest.Transcribe? = null
    private var noise = true
    private var format = "mp3"
    private var token: CancelToken? = null
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var message = "通知から録音できます"
    private var pendingText: String? = null
    private val ws get() = app.workspace

    fun refresh() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "録音ツールバー", NotificationManager.IMPORTANCE_LOW))
        if (app.prefs.toolbarEnabled || busy) {
            if (androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()) nm.notify(ID, notification())
        } else nm.cancel(ID)
    }

    fun notification(): Notification {
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voxcribe).setContentTitle("Voxcribe 録音ツールバー")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true).setOnlyAlertOnce(true)
        when {
            recording -> {
                builder.addAction(0, "停止してコピー", service(STOP))
                builder.addAction(0, "停止してアプリで継続", activity(CONTINUE, MainActivity::class.java))
                builder.addAction(0, "破棄", service(CANCEL))
            }
            processing -> builder.addAction(0, "中止", service(CANCEL))
            else -> {
                builder.addAction(0, "録音開始", startServiceAction())
                if (pendingText != null) builder.addAction(0, "コピー", copyAction())
            }
        }
        return builder.build()
    }

    private fun activity(action: String, target: Class<*>): PendingIntent = PendingIntent.getActivity(
        app, action.hashCode(), Intent(app, target).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun service(action: String): PendingIntent = PendingIntent.getService(
        app, action.hashCode(), Intent(app, ToolbarService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startServiceAction(): PendingIntent = PendingIntent.getForegroundService(
        app, START.hashCode(), Intent(app, ToolbarService::class.java).setAction(START),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun copyAction(): PendingIntent = PendingIntent.getBroadcast(
        app, COPY.hashCode(), Intent(app, ToolbarCommandReceiver::class.java).setAction(COPY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 通知操作によるマイクサービス開始の例外を利用し、Activityを開かず開始する。 */
    fun start(): Boolean {
        if (!app.prefs.toolbarEnabled || busy || ws.isRecording || ws.taskRunning || !ws.recordButtonsEnabled) {
            message = "アプリの録音・処理の終了後に開始してください"; refresh(); return false
        }
        if (!ws.hasMicPermission()) {
            message = "アプリの設定でマイクの使用を許可してください"; refresh(); return false
        }
        val model = Models.validate(app.prefs.toolbarModel)
        if (!app.secrets.has(Models.keyType(model))) {
            message = "設定で${Models.keyType(model)}のAPIキーを登録してください"; refresh(); return false
        }
        pendingText = null
        noise = app.prefs.toolbarNoise
        format = app.prefs.toolbarFormat
        request = AiRequest.Transcribe(File(app.cacheDir, "unused"), if (format == "wav") "audio/wav" else "audio/mpeg",
            model, app.prefs.toolbarThinking, app.prefs.toolbarRephrase, app.prefs.toolbarFiller, false, transient = true)
        recording = true
        ws.recordButtonsEnabled = false
        message = "録音準備中…"
        refresh()
        return true
    }

    fun beginCapture() {
        if (!recording || mic != null || job?.isActive == true) return
        acquireWakeLock()
        job = app.appScope.launch {
            try {
                mic = withContext(Dispatchers.IO) {
                    ws.preparedNoiseOn = null
                    ws.recorder.open(MicProbe.probe(app), noise).also { ws.recorder.start() }
                }
                message = "録音中 — 停止すると文字起こししてコピーします"
                refresh()
            } catch (e: Exception) {
                ws.recorder.cancel()
                finish("録音を開始できませんでした: ${e.message}")
            }
        }
    }

    fun handoff() {
        val settings = mic ?: return
        if (!recording || processing) return
        ws.recorder.paused = true
        ws.openRecordingRequested = true
        ws.notificationRecording = true
        ws.tab = 0
        ws.isRecording = true
        ws.isPaused = true
        ws.recordButtonsEnabled = true
        ws.noiseSwitchEnabled = false
        ws.lastMicSettings = settings
        ws.recordNoise = noise
        ws.recordFormat = format
        ws.recorder.onBlock = { ws.latestBlock = it }
        ws.status = StatusView("通知の録音を一時停止中（再開して継続できます）")
        message = "アプリで継続できます（現在は一時停止中）"
        refresh()
    }

    fun pauseChanged(paused: Boolean) {
        message = if (paused) "アプリで継続できます（現在は一時停止中）" else "アプリで録音を継続中…"
        refresh()
    }

    fun stop() {
        val settings = mic ?: return
        val req = request ?: return
        if (!recording || processing) return
        recording = false
        processing = true
        clearWorkspaceRecording()
        ws.recordButtonsEnabled = false
        message = "文字起こし中…"
        refresh()
        // microphone -> dataSync に更新。録音後も画面OFFで処理を継続する。
        ToolbarService.update(app)
        val currentToken = CancelToken().also { token = it }
        job = app.appScope.launch {
            var raw: File? = null
            val out = File(app.cacheDir, "toolbar_${System.currentTimeMillis()}.$format")
            try {
                val text = withContext(Dispatchers.IO) {
                    raw = ws.recorder.stop() ?: error("録音データがありません")
                    Finalize.run(raw!!, settings.channels, settings.sampleRate, format,
                        agcOff = settings.autoGainControl == false, noiseOn = noise, out = out)
                    var result = ""
                    var failure: String? = null
                    app.runner.run(req.copy(file = out), currentToken) { event ->
                        when (event) {
                            is AiEvent.Text -> result += event.delta
                            is AiEvent.ReplaceText -> result = event.full
                            is AiEvent.Error -> failure = event.content
                            is AiEvent.Cancelled -> failure = event.content
                            else -> Unit
                        }
                    }
                    failure?.let { error(it) }
                    if (currentToken.isCancelled) error("処理を中止しました")
                    result.trim().ifEmpty { error("文字起こし結果が空でした") }
                }
                pendingText = text
                try {
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("文字起こし", text))
                    finish("クリップボードにコピーしました（再コピーもできます）")
                } catch (_: Exception) {
                    finish("文字起こし完了 — 「コピー」を押してください")
                }
            } catch (e: Exception) {
                finish(e.message ?: "文字起こしに失敗しました")
            } finally {
                raw?.delete()
                out.delete()
            }
        }
    }

    fun copy() {
        pendingText?.let {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("文字起こし", it))
        }
    }

    fun cancel() {
        token?.cancel()
        // IOで使用中の音声はjobのfinallyで削除する。ジョブをcancelして後始末を飛ばさない。
        if (processing) { message = "中止しています…"; refresh(); return }
        if (recording && job?.isActive == true) {
            app.appScope.launch { job?.join(); cancel() }
            return
        }
        if (recording) ws.recorder.cancel()
        pendingText = null
        clearWorkspaceRecording()
        finish("録音を破棄しました")
    }

    private fun clearWorkspaceRecording() {
        if (ws.notificationRecording) {
            ws.isRecording = false
            ws.isPaused = false
            ws.notificationRecording = false
            ws.noiseSwitchEnabled = true
            ws.latestBlock = null
            ws.levelText = null
            ws.status = StatusView("通知の録音を終了しました")
        }
        ws.recorder.onBlock = null
    }

    private fun finish(text: String) {
        recording = false; processing = false
        mic = null; request = null; token = null
        ws.recordButtonsEnabled = true
        message = text
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        app.stopService(Intent(app, ToolbarService::class.java))
        refresh()
    }

    private fun acquireWakeLock() {
        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voxcribe:toolbar").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
    }

    fun serviceDestroyed() {
        if (recording) cancel()
        refresh()
    }

    companion object {
        const val CHANNEL = "voxcribe_toolbar"
        const val ID = 1003
        const val START = "toolbar.start"
        const val STOP = "toolbar.stop"
        const val CONTINUE = "toolbar.continue"
        const val COPY = "toolbar.copy"
        const val CANCEL = "toolbar.cancel"
    }
}
