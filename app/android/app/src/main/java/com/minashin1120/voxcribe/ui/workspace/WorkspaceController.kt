package com.minashin1120.voxcribe.ui.workspace

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.GrokLiveSession
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.audio.MicInfo
import com.minashin1120.voxcribe.audio.MicSettings
import com.minashin1120.voxcribe.audio.NativeRecorder
import com.minashin1120.voxcribe.data.BatchRow
import com.minashin1120.voxcribe.data.HistoryRow
import com.minashin1120.voxcribe.data.SavedAudio
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.data.WordRow
import com.minashin1120.voxcribe.data.WordSetRow
import com.minashin1120.voxcribe.ui.common.BadgeSpec
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File

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
class WorkspaceController(internal val app: VoxcribeApp) {
    internal val ctx: Context get() = app
    internal val prefs = app.prefs
    internal val toast = app.toaster
    internal val scope = app.appScope

    // ---------- 設定（localStorage 相当） ----------
    var model by mutableStateOf(Models.validate(prefs.model?.takeIf { v -> Models.ALL.any { it.value == v } } ?: Models.DEFAULT))
        internal set
    var thinking by mutableStateOf(prefs.thinkingFor(model) ?: "LOW")
        internal set
    var noise by mutableStateOf(prefs.noise?.let { it == "true" } ?: true)
        internal set
    var rephrase by mutableStateOf(prefs.rephrase == "true")
        internal set
    var filler by mutableStateOf(prefs.filler == "true")
        internal set
    var format by mutableStateOf(if (prefs.format == "wav") "wav" else "mp3")
        internal set

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
    var openRecordingRequested by mutableStateOf(false)
    internal var notificationRecording = false
    var isRecording by mutableStateOf(false)
        internal set
    var isPaused by mutableStateOf(false)
        internal set
    var recordButtonsEnabled by mutableStateOf(true)
    var noiseSwitchEnabled by mutableStateOf(true)
    var levelText by mutableStateOf<LevelText?>(null)
    val recorder = NativeRecorder(app.cacheDir)
    @Volatile var latestBlock: FloatArray? = null

    // Grok Live (リアルタイム文字起こし)
    // liveText/liveFailedはOkHttpのWebSocketコールバックスレッドから書き込まれ、
    // stopRecording()のコルーチンスレッドから読まれるため、可視性確保に@Volatileが必要。
    // StringBuilderでのin-place追記だと参照自体が変わらず@Volatileの恩恵を受けられないため、
    // 常に新しいStringへ再代入する。
    internal var liveSession: GrokLiveSession? = null
    internal var liveToken: CancelToken? = null
    @Volatile internal var liveText: String = ""
    @Volatile internal var liveInterim: String = ""
    @Volatile internal var liveFailed = false
    @Volatile internal var liveInitText: String = ""

    internal var preparedNoiseOn: Boolean? = null
    internal var micInfo: MicInfo? = null
    internal var lastMicSettings: MicSettings? = null
    internal var recordFormat = "mp3"
    internal var recordNoise = true

    // アップロード
    var selectedFile by mutableStateOf<SelectedFile?>(null)
    var uploadButtonsEnabled by mutableStateOf(false)
    var upload by mutableStateOf(UploadPanel())
    internal var uploadUiActive = false
    internal var uploadCancelled = false

    // 履歴・データ・単語セット
    val history = mutableStateListOf<HistoryRow>()
    var historyLoaded by mutableStateOf(false)
    val files = mutableStateListOf<SavedAudio>()
    var filesLoading by mutableStateOf(false)
    val wordSets = mutableStateListOf<WordSetRow>()
    val manageWords = mutableStateListOf<WordRow>()

    // Batch（Gemini Batch API）。ロジックは WorkspaceBatch.kt
    var batchMode by mutableStateOf(prefs.getString("stt_batch") == "true")
        internal set
    val batches = mutableStateListOf<BatchRow>()
    var batchDonePrompt by mutableStateOf<BatchRow?>(null)
        internal set
    internal val batchDoneQueue = ArrayDeque<BatchRow>()
    internal var batchPollJob: Job? = null

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
    internal var isAppendMode = false
    internal var initText = ""
    internal var sessionText = StringBuilder()
    internal var sessionFailed = false
    internal var sessionCancelled = false
    internal var sessionThoughtReceived = false
    internal var currentToken: CancelToken? = null
    internal var currentJob: Job? = null
    internal var userAborted = false
    var taskRunning by mutableStateOf(false)
        internal set

    // エラー時ダウンロード用のローカル音声
    internal var lastLocalAudio: File? = null
    internal var lastLocalAudioName: String? = null

    internal var started = false

    /** 画面表示時の初期化（index.html の DOMContentLoaded 相当） */
    fun onScreenStart() {
        if (!started) {
            started = true
            loadHistory()
            loadWordSetStatus()
            startBatchPolling()
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


    @Suppress("unused")
    internal suspend fun tick() = delay(1)
}
