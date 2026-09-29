package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.ai.AiEvent
import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.ai.TaskPhase
import com.minashin1120.voxcribe.task.WorkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// AI 処理の実行と結果ストリーム表示（index.html の handleStreamResponse / beginStreamSession 相当）。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= タスク実行（SSE ストリーム相当） =================

internal enum class BeginOn { IMMEDIATE, RESPONSE }

internal fun WorkspaceController.startTask(req: AiRequest, beginOn: BeginOn, uploadProgress: Boolean = false) {
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

internal fun WorkspaceController.showUploadErrorSafe(msg: String) {
    uploadUiActive = true
    showUploadError(msg)
    uploadUiActive = false
}

internal fun WorkspaceController.finishTaskState() {
    taskRunning = false
    currentToken = null
    abortVisible = false
    uploadUiActive = false
    if (selectedFile != null) uploadButtonsEnabled = true
    improveEnabled = true
    syncPostprocessButtons()
    WorkService.update(ctx, recording = isRecording, processing = false)
}

internal fun WorkspaceController.onFailedBeforeStream(message: String, uploadMode: Boolean) {
    if (!isAppendMode) loadHistory()
    if (uploadMode) {
        showUploadError(message)
    } else {
        status = StatusView("エラー")
        errorDownloadVisible = lastLocalAudio != null
    }
    toast.show(message, true)
}

internal fun WorkspaceController.onCancelledBeforeStream(uploadMode: Boolean) {
    if (uploadMode) upload = UploadPanel()
    status = StatusView("停止されました")
    errorDownloadVisible = lastLocalAudio != null
}

internal fun WorkspaceController.beginStreamSession() {
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

internal fun WorkspaceController.finishStreamSession(aborted: Boolean) {
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
                Models.isGeminiStt(model) -> thoughtText = "[Gemini Transcribeは文字起こし専用モデルです。]"
                model == "gemini-3.1-flash-lite" && (thinking == "LOW" || thinking == "MEDIUM") && !sessionThoughtReceived ->
                    thoughtText = "[Flash-Lite は LOW/MEDIUM 設定時に思考プロセスを返しません。表示するには HIGH を選択してください。]"
            }
        }
    }
}

/** 「処理を停止」 */
fun WorkspaceController.abortProcessing() {
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
