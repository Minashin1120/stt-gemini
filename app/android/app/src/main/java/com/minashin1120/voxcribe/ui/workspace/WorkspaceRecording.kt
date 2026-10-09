package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.GrokClient
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.ai.startLiveSession
import com.minashin1120.voxcribe.audio.Finalize
import com.minashin1120.voxcribe.audio.MicProbe
import com.minashin1120.voxcribe.audio.MicProcessingException
import com.minashin1120.voxcribe.audio.MicSettings
import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.task.WorkService
import com.minashin1120.voxcribe.ui.common.BadgeColor
import com.minashin1120.voxcribe.ui.common.BadgeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// マイク準備・録音・Grok Live・録音データの送信（index.html の rec / upl / startGrokLiveSession 相当）。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= マイク準備 =================

private const val MIC_PREPARE_TIMEOUT_MS = 8_000L

fun WorkspaceController.prepareMic() {
    if (app.toolbar.busy || !recordButtonsEnabled) return
    if (!hasMicPermission()) {
        status = StatusView("マイク未準備")
        return
    }
    val noiseOn = noise
    status = StatusView("マイク準備中...")
    recordButtonsEnabled = false
    scope.launch {
        try {
            // 音声HALが応答しない端末でボタンが無効のまま固まらないよう、await側にタイムアウトを掛ける
            val (info, settings) = withTimeoutOrNull(MIC_PREPARE_TIMEOUT_MS) {
                async(Dispatchers.IO) {
                    val info = MicProbe.probe(ctx)
                    info to recorder.open(info, noiseOn)
                }.await()
            } ?: throw MicProcessingException("マイクの準備がタイムアウトしました。再度お試しください")
            micInfo = info
            lastMicSettings = settings
            preparedNoiseOn = noiseOn
            status = StatusView("マイク準備完了（ノイズ除去 ${if (noiseOn) "ON" else "OFF"}）")
            toast.show("マイクの準備が完了しました")
        } catch (e: MicProcessingException) {
            preparedNoiseOn = null
            status = StatusView("マイク準備エラー")
            micErrorMessage = e.message
        } catch (e: Exception) {
            preparedNoiseOn = null
            status = StatusView("マイク準備エラー")
            toast.show(e.message ?: e.toString(), true)
        } finally {
            recordButtonsEnabled = true
        }
    }
}

// ================= 録音 =================

fun WorkspaceController.rec(append: Boolean) {
    if (app.toolbar.busy || isRecording || !recordButtonsEnabled) {
        toast.show("録音または通知の処理が進行中です", true)
        return
    }
    if (!hasMicPermission()) {
        toast.show("マイクの使用が許可されていません", true)
        return
    }
    // Grok Liveは録音開始直後からAPIキーが必要。未設定・不正形式なら録音前に
    // 入力画面を出し、保存後に改めて録音開始する。
    if (Models.isGrokLive(model) && !app.secrets.has(KeyType.XAI)) {
        scope.launch {
            val (proceed, _) = ensureApiKeyForModel(model, false, null, null)
            if (proceed) rec(append)
        }
        return
    }
    recordButtonsEnabled = false
    isAppendMode = append
    if (!append) clearResultUiForNew()
    copyEnabled = false
    status = StatusView("準備中...")
    val noiseOn = noise
    scope.launch {
        try {
            val settings = withContext(Dispatchers.IO) {
                if (preparedNoiseOn == noiseOn && recorder.isOpen() && lastMicSettings != null) {
                    lastMicSettings!!
                } else {
                    val info = MicProbe.probe(ctx)
                    micInfo = info
                    recorder.open(info, noiseOn)
                }
            }
            preparedNoiseOn = null
            lastMicSettings = settings
            recordNoise = noiseOn
            recordFormat = format
            recorder.onBlock = { latestBlock = it }
            if (Models.isGrokLive(model)) startGrokLiveSession(settings) else stopGrokLiveState()
            recorder.start()
            isRecording = true
            isPaused = false
            noiseSwitchEnabled = false
            levelText = LevelText("入力レベルを確認中…", 0)
            WorkService.update(ctx, recording = true, processing = taskRunning)
            status = recordingStatus(settings, noiseOn, full = true)
            val info = micInfo
            when {
                info != null && info.externalConnected && recorder.routedToBuiltIn == false ->
                    toast.show("端末が外部マイクへの切り替えを優先したため、内蔵マイクに固定できませんでした。通話中などは Bluetooth 機器を切断してください", true)
                info != null && info.externalConnected ->
                    toast.show("Bluetooth・イヤフォン等が接続されていますが、内蔵マイクで録音します")
                !noiseOn && settings.processingFullyOff -> toast.show("録音開始（端末音声処理OFFを確認）")
                else -> toast.show(if (noiseOn) "録音開始（ノイズ除去ON）" else "録音開始（ノイズ除去OFF）")
            }
        } catch (e: MicProcessingException) {
            isRecording = false
            noiseSwitchEnabled = true
            recorder.release()
            status = StatusView("エラー")
            micErrorMessage = e.message
        } catch (e: Exception) {
            isRecording = false
            noiseSwitchEnabled = true
            recorder.release()
            status = StatusView("エラー")
            toast.show(e.message ?: e.toString(), true)
        } finally {
            recordButtonsEnabled = true
        }
    }
}

internal fun WorkspaceController.recordingStatus(mic: MicSettings, noiseOn: Boolean, full: Boolean): StatusView {
    val b = mutableListOf<BadgeSpec>()
    if (!full) {
        b += if (noiseOn) BadgeSpec("ノイズ除去: ON", BadgeColor.PRIMARY) else BadgeSpec("ノイズ除去: OFF", BadgeColor.SECONDARY)
        return StatusView("● 録音中...", true, b)
    }
    b += BadgeSpec("内蔵マイク: ${mic.builtInCount}個検出", if (mic.builtInCount >= 2) BadgeColor.SUCCESS else BadgeColor.INFO)
    b += if (noiseOn) BadgeSpec("ノイズ除去: ON", BadgeColor.PRIMARY) else BadgeSpec("ノイズ除去: OFF", BadgeColor.SECONDARY)
    if (!noiseOn && mic.processingFullyOff) b += BadgeSpec("端末処理: OFF確認", BadgeColor.SUCCESS)
    else if (mic.noiseSuppression != null) b += BadgeSpec("端末NS: ${if (mic.noiseSuppression) "ON" else "OFF"}", BadgeColor.DARK)
    mic.autoGainControl?.let { b += BadgeSpec("AGC: ${if (it) "ON" else "OFF"}", BadgeColor.DARK) }
    mic.echoCancellation?.let { b += BadgeSpec("EC: ${if (it) "ON" else "OFF"}", BadgeColor.DARK) }
    b += if (recordFormat == "wav") BadgeSpec("WAV(PCM)", BadgeColor.INFO) else BadgeSpec("MP3 192k", BadgeColor.INFO)
    b += BadgeSpec("安定録音", BadgeColor.SUCCESS)
    b += when {
        mic.channels > 1 -> BadgeSpec("2マイク→モノ統合", BadgeColor.INFO)
        mic.requestedStereo -> BadgeSpec("ステレオ不可→1ch", BadgeColor.WARNING)
        else -> BadgeSpec("マイク入力: 1ch", BadgeColor.SECONDARY)
    }
    val name = mic.deviceLabel.trim()
    if (name.isNotEmpty()) b += BadgeSpec("マイク: " + if (name.length > 14) name.take(14) + "…" else name, BadgeColor.DARK)
    return StatusView("● 録音中...", true, b)
}

fun WorkspaceController.togglePause() {
    isPaused = !isPaused
    recorder.paused = isPaused
    if (notificationRecording) app.toolbar.pauseChanged(isPaused)
    status = if (isPaused) StatusView("一時停止") else recordingStatus(lastMicSettings ?: return, recordNoise, full = false)
}

fun WorkspaceController.cancelRecording() {
    if (notificationRecording) { app.toolbar.cancel(); return }
    isRecording = false
    isPaused = false
    noiseSwitchEnabled = true
    recorder.onBlock = null
    val hadLiveSession = liveSession != null
    liveSession?.cancel()
    stopGrokLiveState()
    if (hadLiveSession) resultText = liveInitText
    scope.launch(Dispatchers.IO) { recorder.cancel() }
    latestBlock = null
    levelText = null
    status = StatusView("キャンセル")
    toast.show("キャンセルしました")
    WorkService.update(ctx, recording = false, processing = taskRunning)
}

/** Grok Live: 録音開始時にxAIへのWebSocketセッションを開く。キー未設定/失敗時は
 * liveFailed=true のまま何もせず、停止時に通常のバッチGrok STTへフォールバックする。 */
internal fun WorkspaceController.startGrokLiveSession(settings: MicSettings) {
    liveText = ""
    liveInterim = ""
    liveFailed = false
    liveInitText = if (isAppendMode && resultText.trim().isNotEmpty()) resultText.trim() + "\n\n" else ""
    resultText = liveInitText
    liveSession = null
    val key = app.secrets.get(KeyType.XAI)
    if (key == null) {
        liveFailed = true
        recorder.onRawBlock = null
        return
    }
    val token = CancelToken()
    liveToken = token
    recorder.onRawBlock = { buf, _ -> liveSession?.sendAudio(floatToPcm16Interleaved(buf)) }
    liveSession = GrokClient.startLiveSession(
        apiKey = key,
        sampleRate = settings.sampleRate,
        channels = settings.channels,
        token = token,
        onStatus = {},
        onPartial = { chIdx, text, isFinal, speechFinal ->
            // 2マイクは同じ音源を別々に拾っているだけなので、表示・確定はchannel 0のみを使う
            if (chIdx == 0) {
                if (isFinal && speechFinal && text.isNotEmpty()) {
                    liveText = if (liveText.isNotEmpty()) liveText + "\n" + text else text
                    liveInterim = ""
                    resultText = liveInitText + liveText
                } else {
                    liveInterim = text
                    resultText = liveInitText + liveText + (if (liveText.isNotEmpty()) "\n" else "") + liveInterim
                }
            }
        },
        onFinalText = { chIdx, text ->
            // transcript.doneはセッション終了時に送られる確定済み全文。speech_final境界の後に
            // 発話された末尾の言葉も含まれるため、積み上げてきたテキストより常に優先する。
            // タイムアウト等でtextが空/未着のこともあるため、その場合はliveTextを保持したままにする
            // (stopRecording側でliveInterimを最終フォールバックとして使う)。
            if (chIdx == 0 && text.isNotEmpty()) {
                liveText = text
                liveInterim = ""
                resultText = liveInitText + liveText
            }
        },
        onError = { liveFailed = true },
    )
}

internal fun WorkspaceController.stopGrokLiveState() {
    liveSession = null
    liveToken = null
    liveText = ""
    liveInterim = ""
    liveFailed = false
    recorder.onRawBlock = null
}

internal fun WorkspaceController.floatToPcm16Interleaved(buf: FloatArray): ByteArray {
    val out = ByteArray(buf.size * 2)
    for (i in buf.indices) {
        val v = (buf[i].coerceIn(-1f, 1f) * 32767).toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}

fun WorkspaceController.stopRecording() {
    if (notificationRecording) { app.toolbar.stop(); return }
    isRecording = false
    isPaused = false
    noiseSwitchEnabled = true
    recorder.onBlock = null
    recorder.onRawBlock = null
    latestBlock = null
    levelText = null
    status = StatusView("解析中...")
    val mic = lastMicSettings
    val fmt = recordFormat
    val noiseOn = recordNoise
    val session = liveSession
    scope.launch {
        try {
            if (session != null) withContext(Dispatchers.IO) { session.finish() }
            val result = withContext(Dispatchers.IO) {
                val raw = recorder.stop() ?: throw IllegalStateException("録音データがありません")
                val ext = if (fmt == "wav") ".wav" else ".mp3"
                val out = File(app.cacheDir, "rec_out_${System.currentTimeMillis()}$ext")
                Finalize.run(
                    raw, mic?.channels ?: 1, mic?.sampleRate ?: 48000, fmt,
                    agcOff = mic?.autoGainControl == false, noiseOn = noiseOn, out = out
                )
            }
            lastMicSettings = null
            WorkService.update(ctx, recording = false, processing = taskRunning)
            val name = if (fmt == "wav") "rec.wav" else "rec.mp3"
            if (session != null) {
                // transcript.doneがタイムアウト等で届かない/空のことがあるため、
                // その場合でも未確定のまま表示されていたliveInterimを最終テキストに含める
                // (でないとリアルタイムでは見えていたのに保存結果が空になってしまう)。
                val text = liveText + (if (liveInterim.isNotEmpty()) (if (liveText.isNotEmpty()) "\n" else "") + liveInterim else "")
                val failed = liveFailed
                stopGrokLiveState()
                if (!failed && text.isNotEmpty()) {
                    finalizeLiveGrok(result.file, name, text)
                } else {
                    // WSが使えなかった/テキストが取れなかった場合は通常のバッチアップロードにフォールバック。
                    // model="grok-live-transcribe"のままでよい(Models.isGrokがtrueを返すため
                    // AiRunner.runSttは通常のGrokClient.transcribeバッチ経路をそのまま使う)。
                    resultText = liveInitText // ライブ表示中の未確定テキストを消してから通常フローへ
                    upl(result.file, name)
                }
            } else {
                upl(result.file, name)
            }
        } catch (e: Exception) {
            lastMicSettings = null
            stopGrokLiveState()
            WorkService.update(ctx, recording = false, processing = taskRunning)
            status = StatusView("エラー")
            toast.show(e.message ?: e.toString(), true)
        }
    }
}

/** Grok Live: WebSocketで受信済みの確定テキストを音声と一緒に端末内に保存する。
 * 既存のAiRunner(/transcribe相当)を経由しない: 文字起こしは既にWebSocket上で完了しているため。 */
internal suspend fun WorkspaceController.finalizeLiveGrok(blob: File, name: String, rawText: String) {
    rememberLocalAudio(blob, name)
    errorDownloadVisible = false
    status = StatusView("保存中...")
    val text = withContext(Dispatchers.IO) { app.runner.applyWordReplacements(rawText) }
    val stored = withContext(Dispatchers.IO) {
        val f = app.audio.newFile(AudioStore.extOf(name))
        blob.copyTo(f, overwrite = true)
        f
    }
    withContext(Dispatchers.IO) {
        if (!isAppendMode) {
            app.db.clearHistory()
            app.audio.deleteAllExcept(stored)
            prefs.lastAudioFile = null
            prefs.lastAudioMime = null
        }
        prefs.lastAudioFile = stored.name
        prefs.lastAudioMime = AudioStore.MIME_BY_EXT[AudioStore.extOf(name)] ?: "audio/mpeg"
        if (text.isNotEmpty()) app.db.insertHistory("transcribe", "Live Audio (Grok)", "", text)
    }
    resultText = liveInitText + text
    status = StatusView("完了")
    copyEnabled = true
    reanalyzeEnabled = true
    deleteAudioEnabled = true
    syncPostprocessButtons()
    toast.show("完了")
    loadHistory()
}

/** 録音データの送信（index.html upl） */
internal suspend fun WorkspaceController.upl(blob: File, name: String) {
    val (proceed, useModel) = ensureApiKeyForModel(model, true, blob, name)
    if (!proceed) {
        status = StatusView("待機中")
        return
    }
    rememberLocalAudio(blob, name)
    errorDownloadVisible = false
    if (isBatchFor(useModel)) {
        submitBatchRecording(blob, name, useModel)
        return
    }
    if (!isAppendMode) clearResultUiForNew()
    status = StatusView("アップロード中...")
    val stored = withContext(Dispatchers.IO) {
        val f = app.audio.newFile(AudioStore.extOf(name))
        blob.copyTo(f, overwrite = true)
        f
    }
    startTask(
        AiRequest.Transcribe(stored, AudioStore.MIME_BY_EXT[AudioStore.extOf(name)] ?: "audio/mpeg", useModel, thinking, rephrase, filler, isAppendMode),
        beginOn = BeginOn.RESPONSE,
    )
}
