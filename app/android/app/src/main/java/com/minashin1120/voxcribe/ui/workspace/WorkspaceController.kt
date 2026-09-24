package com.minashin1120.voxcribe.ui.workspace

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.AiEvent
import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.ai.Prompts
import com.minashin1120.voxcribe.ai.TaskPhase
import com.minashin1120.voxcribe.audio.Finalize
import com.minashin1120.voxcribe.audio.MicInfo
import com.minashin1120.voxcribe.audio.MicProbe
import com.minashin1120.voxcribe.audio.MicProcessingException
import com.minashin1120.voxcribe.audio.MicSettings
import com.minashin1120.voxcribe.audio.NativeRecorder
import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.HistoryRow
import com.minashin1120.voxcribe.data.SavedAudio
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.data.WordRow
import com.minashin1120.voxcribe.data.WordSetRow
import com.minashin1120.voxcribe.task.WorkService
import com.minashin1120.voxcribe.ui.common.BadgeColor
import com.minashin1120.voxcribe.ui.common.BadgeSpec
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import com.minashin1120.voxcribe.util.Downloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ステータスピル（#status）: テキスト + バッジ */
data class StatusView(val text: String, val recording: Boolean = false, val badges: List<BadgeSpec> = emptyList())

data class UploadPanel(
    val visible: Boolean = false,
    val label: String = "アップロード準備中",
    val percent: Int = 0,
    val isError: Boolean = false,
    val errorPercentText: String? = null,
    val cancelVisible: Boolean = false,
    val errorActions: Boolean = false,
)

data class SelectedFile(val uri: Uri, val name: String, val size: Long, val mime: String?)

enum class AkView { INPUT, SWITCH, DISCARD }

data class ApiKeyPrompt(
    val keyType: KeyType,
    val model: String,
    val isRecording: Boolean,
    val audioFile: File?,
    val audioName: String?,
    val view: AkView = AkView.INPUT,
    val error: String? = null,
    val saving: Boolean = false,
    val switchModels: List<Pair<String, String>>? = null,
    val result: CompletableDeferred<Pair<Boolean, String>>,
)

data class LevelText(val text: String, val color: Int) // 0=muted 1=danger 2=warning 3=success

/**
 * index.html のスクリプト（録音・アップロード・ストリーム表示・履歴・単語セット等）を移植した画面ロジック。
 * Application スコープで保持するため、画面を離れても録音・処理状態は保たれる。
 */
class WorkspaceController(private val app: VoxcribeApp) {
    private val ctx: Context get() = app
    private val prefs = app.prefs
    private val toast = app.toaster
    private val scope = app.appScope

    // ---------- 設定（localStorage 相当） ----------
    var model by mutableStateOf(Models.validate(prefs.model?.takeIf { v -> Models.ALL.any { it.value == v } } ?: Models.DEFAULT))
        private set
    var thinking by mutableStateOf(prefs.thinkingFor(model) ?: "LOW")
        private set
    var noise by mutableStateOf(prefs.noise?.let { it == "true" } ?: true)
        private set
    var rephrase by mutableStateOf(prefs.rephrase == "true")
        private set
    var filler by mutableStateOf(prefs.filler == "true")
        private set
    var format by mutableStateOf(if (prefs.format == "wav") "wav" else "mp3")
        private set

    // ---------- UI 状態 ----------
    var tab by mutableStateOf(0)
    var status by mutableStateOf(StatusView("マイク未準備"))
    var abortVisible by mutableStateOf(false)
    var abortEnabled by mutableStateOf(true)
    var errorDownloadVisible by mutableStateOf(false)
    var processingBar by mutableStateOf(false)
    var thinkingOpen by mutableStateOf(false)
    var thoughtText by mutableStateOf("")
    var resultText by mutableStateOf("")
    var copyEnabled by mutableStateOf(false)
    var reanalyzeEnabled by mutableStateOf(false)
    var deleteAudioEnabled by mutableStateOf(false)
    var correctRephraseEnabled by mutableStateOf(false)
    var fixSpacingEnabled by mutableStateOf(false)
    var improveEnabled by mutableStateOf(true)
    var instruction by mutableStateOf("")
    var useAudioForImprove by mutableStateOf(false)

    // 録音
    var isRecording by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var recordButtonsEnabled by mutableStateOf(true)
    var noiseSwitchEnabled by mutableStateOf(true)
    var levelText by mutableStateOf<LevelText?>(null)
    val recorder = NativeRecorder(app.cacheDir)
    @Volatile var latestBlock: FloatArray? = null
    private var preparedNoiseOn: Boolean? = null
    private var micInfo: MicInfo? = null
    private var lastMicSettings: MicSettings? = null
    private var recordFormat = "mp3"
    private var recordNoise = true

    // アップロード
    var selectedFile by mutableStateOf<SelectedFile?>(null)
    var uploadButtonsEnabled by mutableStateOf(false)
    var upload by mutableStateOf(UploadPanel())
    private var uploadUiActive = false
    private var uploadCancelled = false

    // 履歴・データ・単語セット
    val history = mutableStateListOf<HistoryRow>()
    var historyLoaded by mutableStateOf(false)
    val files = mutableStateListOf<SavedAudio>()
    var filesLoading by mutableStateOf(false)
    val wordSets = mutableStateListOf<WordSetRow>()
    val manageWords = mutableStateListOf<WordRow>()

    // ダイアログ
    var deleteModalOpen by mutableStateOf(false)
    var micErrorMessage by mutableStateOf<String?>(null)
    var fileManagerOpen by mutableStateOf(false)
    var wordSetModalOpen by mutableStateOf(false)
    var wordSetManageOpen by mutableStateOf(false)
    var manageSelectedId by mutableStateOf<Long?>(null)
    var apiKeyPrompt by mutableStateOf<ApiKeyPrompt?>(null)
    var confirm by mutableStateOf<ConfirmRequest?>(null)

    // ストリームセッション（beginStreamSession / finishStreamSession）
    private var isAppendMode = false
    private var initText = ""
    private var sessionText = StringBuilder()
    private var sessionFailed = false
    private var sessionCancelled = false
    private var sessionThoughtReceived = false
    private var currentToken: CancelToken? = null
    private var currentJob: Job? = null
    private var userAborted = false
    var taskRunning by mutableStateOf(false)
        private set

    // エラー時ダウンロード用のローカル音声
    private var lastLocalAudio: File? = null
    private var lastLocalAudioName: String? = null

    private var started = false

    /** 画面表示時の初期化（index.html の DOMContentLoaded 相当） */
    fun onScreenStart() {
        if (!started) {
            started = true
            loadHistory()
            loadWordSetStatus()
        } else {
            loadHistory()
        }
        if (tab == 0 && !isRecording && !taskRunning && preparedNoiseOn == null && hasMicPermission()) prepareMic()
        if (taskRunning) toast.show("前回の処理が続行中です")
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ================= 設定変更 =================

    fun selectModel(value: String) {
        prefs.setThinkingFor(model, thinking)
        model = value
        prefs.model = value
        thinking = prefs.thinkingFor(value) ?: "LOW"
        if (Models.isStt(value)) thinkingOpen = false
        syncPostprocessButtons()
    }

    fun setThinkingLevel(v: String) { thinking = v; prefs.setThinkingFor(model, v) }
    fun setRephraseOn(v: Boolean) { rephrase = v; prefs.rephrase = v.toString() }
    fun setFillerOn(v: Boolean) { filler = v; prefs.filler = v.toString() }
    fun setFormatValue(v: String) { format = v; prefs.format = v }

    /** ノイズ除去スイッチ変更時はマイクを自動で再準備（Web版と同じ） */
    fun setNoiseOn(v: Boolean) {
        noise = v
        prefs.noise = v.toString()
        if (!isRecording) prepareMic()
    }

    fun onTabChange(i: Int) {
        tab = i
        if (i == 1) {
            if (preparedNoiseOn != null && !isRecording) {
                recorder.release()
                preparedNoiseOn = null
                status = StatusView("待機中")
            }
        } else if (!isRecording && preparedNoiseOn == null && hasMicPermission()) {
            prepareMic()
        }
    }

    val postprocessVisible get() = Models.isLite(model)

    fun syncPostprocessButtons() {
        val hasText = resultText.trim().isNotEmpty()
        correctRephraseEnabled = hasText && !taskRunning
        fixSpacingEnabled = Models.isLite(model) && hasText && !taskRunning
    }

    fun onResultEdited(v: String) {
        resultText = v
        syncPostprocessButtons()
    }

    // ================= マイク準備 =================

    fun prepareMic() {
        if (!hasMicPermission()) {
            status = StatusView("マイク未準備")
            return
        }
        val noiseOn = noise
        status = StatusView("マイク準備中...")
        recordButtonsEnabled = false
        scope.launch {
            try {
                val (info, settings) = withContext(Dispatchers.IO) {
                    val info = MicProbe.probe(ctx)
                    info to recorder.open(info, noiseOn)
                }
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

    fun rec(append: Boolean) {
        if (!hasMicPermission()) {
            toast.show("マイクの使用が許可されていません", true)
            return
        }
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
            }
        }
    }

    private fun recordingStatus(mic: MicSettings, noiseOn: Boolean, full: Boolean): StatusView {
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

    fun togglePause() {
        isPaused = !isPaused
        recorder.paused = isPaused
        status = if (isPaused) StatusView("一時停止") else recordingStatus(lastMicSettings ?: return, recordNoise, full = false)
    }

    fun cancelRecording() {
        isRecording = false
        isPaused = false
        noiseSwitchEnabled = true
        recorder.onBlock = null
        scope.launch(Dispatchers.IO) { recorder.cancel() }
        latestBlock = null
        levelText = null
        status = StatusView("キャンセル")
        toast.show("キャンセルしました")
        WorkService.update(ctx, recording = false, processing = taskRunning)
    }

    fun stopRecording() {
        isRecording = false
        isPaused = false
        noiseSwitchEnabled = true
        recorder.onBlock = null
        latestBlock = null
        levelText = null
        status = StatusView("解析中...")
        val mic = lastMicSettings
        val fmt = recordFormat
        val noiseOn = recordNoise
        scope.launch {
            try {
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
                upl(result.file, if (fmt == "wav") "rec.wav" else "rec.mp3")
            } catch (e: Exception) {
                lastMicSettings = null
                WorkService.update(ctx, recording = false, processing = taskRunning)
                status = StatusView("エラー")
                toast.show(e.message ?: e.toString(), true)
            }
        }
    }

    /** 録音データの送信（index.html upl） */
    private suspend fun upl(blob: File, name: String) {
        val (proceed, useModel) = ensureApiKeyForModel(model, true, blob, name)
        if (!proceed) {
            status = StatusView("待機中")
            return
        }
        rememberLocalAudio(blob, name)
        errorDownloadVisible = false
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

    // ================= アップロード =================

    fun onFilePicked(uri: Uri) {
        var name = "audio"
        var size = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        val mime = ctx.contentResolver.getType(uri)
        val ext = AudioStore.extOf(name)
        if (!(mime?.startsWith("audio/") == true || ext == ".mp3" || ext == ".wav" || ext == ".m4a")) {
            toast.show("音声ファイルを選択してください", true)
            return
        }
        upload = UploadPanel()
        errorDownloadVisible = false
        selectedFile = SelectedFile(uri, name, size, mime)
        lastLocalAudio = null
        lastLocalAudioName = name
        uploadButtonsEnabled = true
        status = StatusView("ファイルが選択されました")
    }

    fun removeSelectedFile() {
        selectedFile = null
        uploadButtonsEnabled = false
        upload = UploadPanel()
        errorDownloadVisible = false
        lastLocalAudio = null
        lastLocalAudioName = null
        status = StatusView("待機中")
    }

    private fun showUploadProgress(percent: Int, label: String) {
        if (!uploadUiActive) return
        val pct = percent.coerceIn(0, 100)
        upload = upload.copy(visible = true, label = label, percent = pct, isError = false, errorPercentText = null, cancelVisible = !uploadCancelled, errorActions = false)
        status = StatusView("$label $pct%")
    }

    private fun showUploadError(message: String) {
        upload = upload.copy(visible = true, isError = true, label = message, errorPercentText = if (upload.percent > 0) "${upload.percent}%" else "失敗", cancelVisible = false, errorActions = lastLocalAudio != null)
        status = StatusView(message)
        errorDownloadVisible = lastLocalAudio != null
    }

    fun uploadFile(append: Boolean) {
        val sel = selectedFile ?: return
        scope.launch {
            val (proceed, useModel) = ensureApiKeyForModel(model, false, null, null)
            if (!proceed) {
                status = StatusView("待機中")
                return@launch
            }
            isAppendMode = append
            if (!append) clearResultUiForNew()
            uploadUiActive = true
            uploadCancelled = false
            uploadButtonsEnabled = false
            showUploadProgress(0, "アップロード中")
            val ext = AudioStore.extOf(sel.name)
            val mime = AudioStore.MIME_BY_EXT[ext]
            if (mime == null) {
                showUploadError("対応していない音声形式です")
                toast.show("対応していない音声形式です", true)
                uploadUiActive = false
                uploadButtonsEnabled = true
                return@launch
            }
            // 端末内の保存領域へ取り込み（サーバーへのアップロードに相当）
            val stored = try {
                withContext(Dispatchers.IO) {
                    val f = app.audio.newFile(ext)
                    ctx.contentResolver.openInputStream(sel.uri)!!.use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var copied = 0L
                            var lastPct = -1
                            while (true) {
                                if (uploadCancelled) throw kotlinx.coroutines.CancellationException()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                copied += n
                                val pct = if (sel.size > 0) (copied * 50 / sel.size).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { showUploadProgress(pct, "アップロード中") }
                                }
                            }
                        }
                    }
                    f
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                upload = UploadPanel()
                status = StatusView("停止されました")
                uploadUiActive = false
                uploadButtonsEnabled = true
                return@launch
            } catch (e: Exception) {
                showUploadError("ファイルの読み込みに失敗しました")
                toast.show("ファイルの読み込みに失敗しました", true)
                uploadUiActive = false
                uploadButtonsEnabled = true
                return@launch
            }
            lastLocalAudio = stored
            lastLocalAudioName = sel.name
            startTask(
                AiRequest.Transcribe(stored, mime, useModel, thinking, rephrase, filler, append),
                beginOn = BeginOn.RESPONSE,
                uploadProgress = true,
            )
        }
    }

    fun cancelUpload() {
        uploadCancelled = true
        upload = upload.copy(cancelVisible = false)
        currentToken?.cancel()
        userAborted = true
    }

    // ================= タスク実行（SSE ストリーム相当） =================

    private enum class BeginOn { IMMEDIATE, RESPONSE }

    private fun startTask(req: AiRequest, beginOn: BeginOn, uploadProgress: Boolean = false) {
        if (taskRunning) {
            status = StatusView("エラー")
            toast.show("別の処理が実行中です。完了後に再度お試しください。", true)
            if (uploadProgress) {
                uploadUiActive = false
                uploadButtonsEnabled = true
                showUploadErrorSafe("別の処理が実行中です。完了後に再度お試しください。")
            }
            return
        }
        val token = CancelToken()
        currentToken = token
        userAborted = false
        taskRunning = true
        abortEnabled = true
        WorkService.update(ctx, recording = isRecording, processing = true)
        var begun = false
        if (beginOn == BeginOn.IMMEDIATE) {
            beginStreamSession()
            begun = true
        }
        currentJob = scope.launch {
            val events = kotlinx.coroutines.channels.Channel<AiEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val worker = launch(Dispatchers.IO) {
                app.runner.run(req, token) { events.trySend(it) }
                events.close()
            }
            for (ev in events) {
                when (ev) {
                    is AiEvent.UploadProgress -> if (uploadProgress && !begun && ev.total > 0) {
                        val pct = 50 + (ev.sent * 50 / ev.total).toInt()
                        showUploadProgress(pct, if (pct >= 100) "アップロード完了・サーバーで処理中..." else "アップロード中")
                    }
                    is AiEvent.Status -> {
                        if (!begun && ev.content != TaskPhase.SENDING) {
                            beginStreamSession()
                            begun = true
                            status = StatusView("解析中...")
                        }
                        if (begun) status = StatusView(ev.content)
                    }
                    is AiEvent.Thought -> {
                        if (!begun) { beginStreamSession(); begun = true }
                        sessionThoughtReceived = true
                        thoughtText += ev.delta
                    }
                    is AiEvent.Text -> {
                        if (!begun) { beginStreamSession(); begun = true }
                        sessionText.append(ev.delta)
                        resultText = initText + sessionText
                    }
                    is AiEvent.ReplaceText -> {
                        if (!begun) { beginStreamSession(); begun = true }
                        sessionText = StringBuilder(ev.full)
                        resultText = initText + sessionText
                    }
                    is AiEvent.Done -> Unit
                    is AiEvent.Error -> {
                        if (!begun) {
                            // レスポンス開始前の失敗（index.html のアップロード失敗 / 非200応答に相当）
                            onFailedBeforeStream(ev.content, uploadProgress)
                            finishTaskState()
                            worker.join()
                            return@launch
                        }
                        sessionFailed = true
                        toast.show(ev.content, true)
                    }
                    is AiEvent.Cancelled -> {
                        if (!begun) {
                            onCancelledBeforeStream(uploadProgress)
                            finishTaskState()
                            worker.join()
                            return@launch
                        }
                        sessionCancelled = true
                    }
                }
            }
            worker.join()
            finishStreamSession(aborted = userAborted && !sessionCancelled)
            if (uploadProgress) {
                upload = UploadPanel()
            }
            finishTaskState()
        }
    }

    private fun showUploadErrorSafe(msg: String) {
        uploadUiActive = true
        showUploadError(msg)
        uploadUiActive = false
    }

    private fun finishTaskState() {
        taskRunning = false
        currentToken = null
        abortVisible = false
        uploadUiActive = false
        if (selectedFile != null) uploadButtonsEnabled = true
        improveEnabled = true
        syncPostprocessButtons()
        WorkService.update(ctx, recording = isRecording, processing = false)
    }

    private fun onFailedBeforeStream(message: String, uploadMode: Boolean) {
        if (!isAppendMode) loadHistory()
        if (uploadMode) {
            showUploadError(message)
        } else {
            status = StatusView("エラー")
            errorDownloadVisible = lastLocalAudio != null
        }
        toast.show(message, true)
    }

    private fun onCancelledBeforeStream(uploadMode: Boolean) {
        if (uploadMode) upload = UploadPanel()
        status = StatusView("停止されました")
        errorDownloadVisible = lastLocalAudio != null
    }

    private fun beginStreamSession() {
        upload = upload.copy(visible = false)
        errorDownloadVisible = false
        processingBar = true
        sessionText = StringBuilder()
        sessionFailed = false
        sessionCancelled = false
        sessionThoughtReceived = false
        if (!isAppendMode) {
            resultText = ""
            thoughtText = ""
            initText = ""
            copyEnabled = false
            reanalyzeEnabled = false
            deleteAudioEnabled = false
            correctRephraseEnabled = false
            fixSpacingEnabled = false
            history.clear()
            historyLoaded = true
        } else {
            val cur = resultText.trim()
            initText = if (cur.isNotEmpty()) cur + "\n\n" else ""
            thoughtText = ""
        }
        if (thinking != "MINIMAL" && !Models.isStt(model)) thinkingOpen = true
        abortVisible = true
    }

    private fun finishStreamSession(aborted: Boolean) {
        processingBar = false
        abortVisible = false
        if (aborted) return
        when {
            sessionCancelled -> {
                status = StatusView("停止されました")
                errorDownloadVisible = lastLocalAudio != null
            }
            sessionFailed -> {
                status = StatusView("エラー")
                copyEnabled = false
                reanalyzeEnabled = false
                deleteAudioEnabled = false
                fixSpacingEnabled = false
                correctRephraseEnabled = false
                errorDownloadVisible = lastLocalAudio != null
            }
            else -> {
                status = StatusView("完了")
                copyEnabled = true
                reanalyzeEnabled = true
                deleteAudioEnabled = true
                syncPostprocessButtons()
                toast.show("完了")
                loadHistory()
                when {
                    Models.isGrok(model) -> thoughtText = "[Grok STT は思考プロセスを提供しません。APIが直接文字起こし結果を返します。]"
                    Models.isOpenAi(model) -> thoughtText = "[OpenAIモデルは思考プロセスを提供しません。APIが直接文字起こし結果を返します。]"
                    model == "gemini-3.1-flash-lite" && (thinking == "LOW" || thinking == "MEDIUM") && !sessionThoughtReceived ->
                        thoughtText = "[Flash-Lite は LOW/MEDIUM 設定時に思考プロセスを返しません。表示するには HIGH を選択してください。]"
                }
            }
        }
    }

    /** 「処理を停止」 */
    fun abortProcessing() {
        if (!abortEnabled) return
        abortEnabled = false
        userAborted = true
        status = StatusView("停止しています...")
        currentToken?.cancel()
        scope.launch {
            currentJob?.join()
            status = StatusView("停止されました")
            toast.show("処理を停止しました")
            abortVisible = false
            abortEnabled = true
        }
    }

    // ================= 再分析・改善・後処理 =================

    fun reanalyze() {
        scope.launch {
            val (proceed, useModel) = ensureApiKeyForModel(model, false, null, null)
            if (!proceed) return@launch
            status = StatusView("再分析中...")
            isAppendMode = false
            startTask(AiRequest.Reanalyze(useModel, thinking, rephrase, filler), BeginOn.RESPONSE)
        }
    }

    fun improve() {
        val t = resultText
        val i = instruction
        if (t.isEmpty() || i.isEmpty()) {
            toast.show("入力してください", true)
            return
        }
        status = StatusView("改善中...")
        improveEnabled = false
        isAppendMode = false
        instruction = ""
        startTask(AiRequest.Improve(t, i, model, thinking, useAudioForImprove), BeginOn.RESPONSE)
    }

    fun fixSpacing() {
        val t = resultText
        if (t.isEmpty()) {
            toast.show("テキストがありません", true)
            return
        }
        isAppendMode = false
        status = StatusView("間隔修正中...")
        fixSpacingEnabled = false
        startTask(AiRequest.Improve(t, Prompts.FIX_SPACING_INSTRUCTION, model, thinking, false), BeginOn.RESPONSE)
    }

    fun correctRephrase() {
        val t = resultText
        if (t.trim().isEmpty()) {
            toast.show("テキストがありません", true)
            return
        }
        scope.launch {
            val (proceed, useModel) = ensureApiKeyForModel(model, false, null, null)
            if (!proceed) return@launch
            isAppendMode = false
            status = StatusView("言い直し修正中...")
            correctRephraseEnabled = false
            startTask(AiRequest.CorrectRephrase(t, useModel, thinking), BeginOn.RESPONSE)
        }
    }

    fun copyResult() {
        copyToClipboard(resultText)
        toast.show("コピーしました")
    }

    fun copyToClipboard(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Voxcribe", text))
    }

    fun deleteAudio() {
        deleteModalOpen = false
        val name = prefs.lastAudioFile
        val f = app.audio.resolve(name)
        if (name == null || f == null) {
            toast.show("なし", true)
            return
        }
        f.delete()
        prefs.lastAudioFile = null
        prefs.lastAudioMime = null
        toast.show("削除しました")
        status = StatusView("削除済み")
        reanalyzeEnabled = false
        deleteAudioEnabled = false
    }

    // ================= 一括削除 =================

    fun requestResetAll() {
        confirm = ConfirmRequest("すべての履歴と保存データを削除して、完全に新しいセッションを開始しますか？") {
            try {
                app.db.clearHistory()
                app.audio.deleteAllExcept(null)
                prefs.lastAudioFile = null
                prefs.lastAudioMime = null
                toast.show("セッションと保存データをリセットしました")
                resultText = ""
                thoughtText = ""
                reanalyzeEnabled = false
                deleteAudioEnabled = false
                fixSpacingEnabled = false
                correctRephraseEnabled = false
                loadHistory()
            } catch (_: Exception) {
                toast.show("リセットに失敗しました", true)
            }
        }
    }

    private fun clearResultUiForNew() {
        resultText = ""
        thoughtText = ""
        initText = ""
        copyEnabled = false
        reanalyzeEnabled = false
        deleteAudioEnabled = false
        fixSpacingEnabled = false
        correctRephraseEnabled = false
    }

    // ================= 履歴 =================

    fun loadHistory() {
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                app.db.historySince(System.currentTimeMillis() - prefs.retentionMinutes * 60_000L, newestFirst = true)
            }
            history.clear()
            history.addAll(rows)
            historyLoaded = true
        }
    }

    fun requestDeleteHistory(id: Long) {
        confirm = ConfirmRequest("この履歴を削除しますか？") {
            if (app.db.deleteHistory(id)) {
                toast.show("履歴を削除しました")
                loadHistory()
            } else toast.show("削除失敗", true)
        }
    }

    fun historyTime(row: HistoryRow): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(row.timestampMs))

    // ================= 保存データ =================

    fun openFileManager() {
        fileManagerOpen = true
        loadFileMetadata()
    }

    fun loadFileMetadata() {
        filesLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { app.audio.list() }
            files.clear()
            files.addAll(list)
            filesLoading = false
        }
    }

    fun requestDeleteFile(f: SavedAudio) {
        confirm = ConfirmRequest("このファイルを削除しますか？") {
            if (f.file.delete()) {
                if (prefs.lastAudioFile == f.file.name) {
                    prefs.lastAudioFile = null
                    prefs.lastAudioMime = null
                }
                toast.show("ファイルを削除しました")
                loadFileMetadata()
            } else toast.show("削除失敗", true)
        }
    }

    /** 100MB超ファイルの「並列DL」: 端末のダウンロードフォルダへ書き出す */
    fun exportFile(f: SavedAudio) {
        toast.show("並列ダウンロード開始 (${maxOf(1, ((f.size + 10L * 1024 * 1024 - 1) / (10L * 1024 * 1024)).toInt())}分割)...")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Downloads.save(ctx, f.file, f.file.name, Downloads.mimeFor(f.file.name)) }
            if (ok) toast.show("ダウンロード完了") else toast.show("ダウンロード失敗", true)
        }
    }

    // ================= エラー時の音声ダウンロード =================

    private fun rememberLocalAudio(file: File, name: String) {
        lastLocalAudio = file
        lastLocalAudioName = name
    }

    fun downloadLocalAudio() {
        val sel = selectedFile
        val f = lastLocalAudio
        if (f == null && sel == null) {
            toast.show("ダウンロードできる音声がありません", true)
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (f != null) Downloads.save(ctx, f, lastLocalAudioName ?: "audio.mp3", Downloads.mimeFor(lastLocalAudioName ?: "audio.mp3"))
                else {
                    val tmp = File(app.cacheDir, "dl_${System.currentTimeMillis()}")
                    try {
                        ctx.contentResolver.openInputStream(sel!!.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        Downloads.save(ctx, tmp, sel.name, Downloads.mimeFor(sel.name))
                    } finally {
                        tmp.delete()
                    }
                }
            }
            if (ok) toast.show("音声のダウンロードを開始しました") else toast.show("ダウンロード失敗", true)
        }
    }

    // ================= 単語セット =================

    fun loadWordSetStatus() {
        scope.launch {
            val sets = withContext(Dispatchers.IO) { app.db.wordSets() }
            wordSets.clear()
            wordSets.addAll(sets)
        }
    }

    val activeSetNames: List<String> get() = wordSets.filter { it.isActive }.map { it.name }

    fun toggleWordSet(id: Long) {
        val r = app.db.toggleWordSet(id)
        if (r != null) {
            toast.show("単語セットの有効/無効を切り替えました")
            loadWordSetStatus()
        } else toast.show("切り替え失敗", true)
    }

    fun resetWordSets() {
        app.db.resetWordSets()
        toast.show("すべてのセットを無効化しました")
        loadWordSetStatus()
    }

    fun openWordSetManage(selectId: Long? = null) {
        wordSetModalOpen = false
        wordSetManageOpen = true
        refreshManageList(selectId)
    }

    fun backToWordSetModal() {
        wordSetManageOpen = false
        wordSetModalOpen = true
        loadWordSetStatus()
    }

    fun refreshManageList(selectId: Long?) {
        manageSelectedId = selectId
        loadWordSetStatus()
        manageWords.clear()
        if (selectId != null) manageWords.addAll(app.db.words(selectId))
    }

    fun createWordSet(name: String) {
        val n = name.trim().take(100).ifEmpty { "新セット" }
        val id = app.db.createWordSet(n)
        refreshManageList(id)
    }

    fun requestDeleteWordSet(id: Long) {
        confirm = ConfirmRequest("このセットを削除しますか？") {
            app.db.deleteWordSet(id)
            refreshManageList(null)
        }
    }

    fun addWord(setId: Long, reading: String, replacement: String): Boolean {
        val r = reading.trim().take(255)
        val p = replacement.trim().take(255)
        if (r.isEmpty() || p.isEmpty()) return false
        app.db.addWord(setId, r, p)
        refreshManageList(setId)
        return true
    }

    fun deleteWord(id: Long) {
        app.db.deleteWord(id)
        refreshManageList(manageSelectedId)
    }

    suspend fun generateYomigana(word: String): String? = withContext(Dispatchers.IO) {
        try {
            app.runner.yomigana(word, prefs.yomiganaModel)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast.show("読みの自動生成に失敗しました: ${e.message ?: ""}", true) }
            null
        }
    }

    // ================= APIキー確認（ensureApiKeyForModel） =================

    private suspend fun ensureApiKeyForModel(m: String, isRecording: Boolean, audioFile: File?, audioName: String?): Pair<Boolean, String> {
        val type = Models.keyType(m)
        if (app.secrets.has(type)) return true to m
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        apiKeyPrompt = ApiKeyPrompt(type, m, isRecording, audioFile, audioName, result = deferred)
        return deferred.await()
    }

    fun akSave(input: String) {
        val p = apiKeyPrompt ?: return
        val key = input.trim()
        if (key.isEmpty()) {
            apiKeyPrompt = p.copy(error = "APIキーを入力してください")
            return
        }
        if (key.length > 512) {
            apiKeyPrompt = p.copy(error = "APIキーが長すぎます")
            return
        }
        apiKeyPrompt = p.copy(saving = true, error = null)
        try {
            app.secrets.put(p.keyType, key)
        } catch (_: Exception) {
            apiKeyPrompt = p.copy(saving = false, error = "保存に失敗しました")
            return
        }
        apiKeyPrompt = null
        p.result.complete(true to p.model)
    }

    fun akShowSwitch() {
        val p = apiKeyPrompt ?: return
        val models = mutableListOf<Pair<String, String>>()
        if (app.secrets.has(KeyType.GEMINI)) models += Models.GEMINI.map { it.value to it.label }
        if (app.secrets.has(KeyType.OPENAI)) models += listOf("gpt-transcribe" to "GPT-Transcribe", "gpt-live-transcribe" to "GPT-Live Transcribe")
        if (app.secrets.has(KeyType.XAI)) models += "grok-stt" to "Grok STT"
        apiKeyPrompt = p.copy(view = AkView.SWITCH, switchModels = models)
    }

    fun akPickModel(value: String) {
        val p = apiKeyPrompt ?: return
        selectModel(value)
        apiKeyPrompt = null
        p.result.complete(true to value)
    }

    fun akBackToInput() {
        apiKeyPrompt = apiKeyPrompt?.copy(view = AkView.INPUT)
    }

    fun akClose() {
        val p = apiKeyPrompt ?: return
        if (p.isRecording) {
            apiKeyPrompt = p.copy(view = AkView.DISCARD)
        } else {
            apiKeyPrompt = null
            p.result.complete(false to p.model)
        }
    }

    fun akConfirmDiscard() {
        val p = apiKeyPrompt ?: return
        apiKeyPrompt = null
        p.result.complete(false to p.model)
    }

    fun akDownload() {
        val p = apiKeyPrompt ?: return
        val f = p.audioFile ?: return
        scope.launch {
            val name = p.audioName ?: "recording.mp3"
            val ok = withContext(Dispatchers.IO) { Downloads.save(ctx, f, name, Downloads.mimeFor(name)) }
            if (ok) toast.show("音声のダウンロードを開始しました") else toast.show("ダウンロード失敗", true)
        }
    }

    // ================= 全データ削除（設定画面） =================

    fun wipeAllData() {
        currentToken?.cancel()
        if (isRecording) cancelRecording()
        app.db.wipeAll()
        app.audio.deleteAllExcept(null)
        app.secrets.clearAll()
        prefs.clearAll()
        history.clear()
        wordSets.clear()
        resultText = ""
        thoughtText = ""
        status = StatusView("マイク未準備")
        preparedNoiseOn = null
        recorder.release()
    }

    @Suppress("unused")
    private suspend fun tick() = delay(1)
}
