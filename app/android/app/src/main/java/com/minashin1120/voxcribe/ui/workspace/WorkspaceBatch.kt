package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.ai.AiException
import com.minashin1120.voxcribe.ai.CancelToken
import com.minashin1120.voxcribe.ai.CancelledException
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.BatchRow
import com.minashin1120.voxcribe.task.BatchWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Batch（Gemini Batch API）の投入・一覧・完了検知・取り込み。WorkspaceController の拡張関数。
// 状態は WorkspaceController.kt（batchMode / batches / batchDonePrompt）。Web版の static/js/index/batch.js に相当。

private const val BATCH_POLL_MS = 60_000L

/** 「Batchで実行」が有効で、そのモデルが公式 Batch 対応のときだけ true */
internal fun WorkspaceController.isBatchFor(model: String) = batchMode && Models.supportsBatch(model)

fun WorkspaceController.setBatchModeOn(v: Boolean) {
    batchMode = v
    prefs.putString("stt_batch", v.toString())
}

// ---------- 通知済み ID（「後で」を選んだジョブを再度ダイアログにしない） ----------

private fun WorkspaceController.notifiedBatchIds(): MutableList<Long> =
    prefs.getString("batch_notified")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toMutableList() ?: mutableListOf()

private fun WorkspaceController.markBatchNotified(id: Long) {
    val ids = notifiedBatchIds()
    if (id !in ids) ids += id
    prefs.putString("batch_notified", ids.takeLast(200).joinToString(","))
}

// ---------- 一覧・ポーリング ----------

fun WorkspaceController.loadBatches() {
    scope.launch {
        val rows = withContext(Dispatchers.IO) { app.db.batches() }
        batches.clear()
        batches.addAll(rows)
    }
}

/** 進行中ジョブを問い合わせ、一覧を更新し、新たに完了したジョブを確認ダイアログの待ち行列へ積む */
internal suspend fun WorkspaceController.refreshBatchesOnce() {
    val (rows, done) = withContext(Dispatchers.IO) {
        val done = app.batchRunner.refreshAll()
        app.db.batches() to done
    }
    batches.clear()
    batches.addAll(rows)
    val notified = notifiedBatchIds()
    // 今回完了したもの＋過去に完了して未取り込み・未通知のもの
    rows.filter { it.status == "succeeded" && !it.imported && it.id !in notified && batchDoneQueue.none { q -> q.id == it.id } && batchDonePrompt?.id != it.id }
        .forEach { batchDoneQueue.addLast(it) }
    if (done.isNotEmpty()) toast.show("Batch処理が完了しました")
    // アプリを閉じていても完了を通知できるよう、進行中のジョブがある間だけ定期ワーカーを動かす
    if (rows.any { it.status == "running" }) BatchWork.ensureScheduled(ctx) else BatchWork.cancel(ctx)
    showNextBatchPrompt()
}

/** 進行中ジョブがある間、60秒ごとに状態を確認する（アプリ起動中のみ。停止中は WorkManager が通知する） */
fun WorkspaceController.startBatchPolling() {
    if (batchPollJob?.isActive == true) return
    batchPollJob = scope.launch {
        while (true) {
            refreshBatchesOnce()
            if (batches.none { it.status == "running" }) break
            delay(BATCH_POLL_MS)
        }
    }
}

fun WorkspaceController.refreshBatches() {
    scope.launch { refreshBatchesOnce(); startBatchPolling() }
}

private fun WorkspaceController.showNextBatchPrompt() {
    if (batchDonePrompt == null) batchDonePrompt = batchDoneQueue.removeFirstOrNull()
}

/** 完了ダイアログの結果。doImport=true なら取り込む、false なら後で（一覧からいつでも取り込める） */
fun WorkspaceController.answerBatchPrompt(doImport: Boolean) {
    val job = batchDonePrompt ?: return
    batchDonePrompt = null
    markBatchNotified(job.id)
    if (doImport) importBatch(job.id)
    showNextBatchPrompt()
}

// ---------- 取り込み・取消・削除 ----------

/** 結果を履歴へ追加し（既存履歴は消さない）、結果欄にも反映する */
fun WorkspaceController.importBatch(id: Long) {
    scope.launch {
        try {
            val row = withContext(Dispatchers.IO) { app.batchRunner.importJob(id) } ?: return@launch
            markBatchNotified(id)
            resultText = row.resultText
            copyEnabled = true
            reanalyzeEnabled = app.audio.resolve(prefs.lastAudioFile) != null
            deleteAudioEnabled = reanalyzeEnabled
            syncPostprocessButtons()
            loadHistory()
            loadBatches()
            toast.show("Batchの結果を取り込みました")
        } catch (e: AiException) {
            toast.show(e.message ?: "取り込みに失敗しました", true)
        } catch (e: Exception) {
            toast.show("取り込みに失敗しました", true)
        }
    }
}

fun WorkspaceController.cancelBatch(id: Long) {
    scope.launch {
        withContext(Dispatchers.IO) { app.batchRunner.cancel(id) }
        loadBatches()
    }
}

fun WorkspaceController.deleteBatch(id: Long) {
    scope.launch {
        withContext(Dispatchers.IO) { app.batchRunner.delete(id) }
        loadBatches()
    }
}

// ---------- 投入 ----------

/** Batch 投入の共通処理（IO スレッドで block を実行し、結果をトースト/ステータスに反映） */
private suspend fun WorkspaceController.runBatchSubmit(block: (CancelToken) -> Long): Boolean {
    status = StatusView("Batchに投入中...")
    return try {
        withContext(Dispatchers.IO) { block(CancelToken()) }
        status = StatusView("Batchに投入しました")
        toast.show("Batchに投入しました（完了後に取り込めます）")
        loadBatches()
        startBatchPolling()
        true
    } catch (e: CancelledException) {
        status = StatusView("停止されました")
        false
    } catch (e: AiException) {
        status = StatusView("エラー")
        toast.show(e.message ?: "Batchの投入に失敗しました", true)
        false
    } catch (e: Exception) {
        status = StatusView("エラー")
        toast.show("Batchの投入に失敗しました", true)
        false
    }
}

/** アップロードタブで選んだファイルを Batch へ投入（履歴・結果欄は変えない） */
internal suspend fun WorkspaceController.submitBatchUpload(sel: SelectedFile, mime: String, model: String) {
    uploadButtonsEnabled = false
    try {
        val ext = AudioStore.extOf(sel.name)
        val stored = try {
            withContext(Dispatchers.IO) {
                val f = app.audio.newFile(ext)
                ctx.contentResolver.openInputStream(sel.uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            }
        } catch (e: Exception) {
            toast.show("ファイルの読み込みに失敗しました", true)
            status = StatusView("エラー")
            return
        }
        runBatchSubmit { token -> app.batchRunner.submitTranscribe(stored, mime, model, thinking, rephrase, filler, token) }
    } finally {
        uploadButtonsEnabled = true
    }
}

/** 録音データを Batch へ投入 */
internal suspend fun WorkspaceController.submitBatchRecording(blob: File, name: String, model: String) {
    val ext = AudioStore.extOf(name)
    val mime = AudioStore.MIME_BY_EXT[ext] ?: "audio/mpeg"
    val stored = withContext(Dispatchers.IO) {
        app.audio.newFile(ext).also { blob.copyTo(it, overwrite = true) }
    }
    if (!runBatchSubmit { token -> app.batchRunner.submitTranscribe(stored, mime, model, thinking, rephrase, filler, token) }) {
        errorDownloadVisible = lastLocalAudio != null
    }
}

internal suspend fun WorkspaceController.submitBatchReanalyze(model: String) {
    runBatchSubmit { token -> app.batchRunner.submitReanalyze(model, thinking, rephrase, filler, token) }
}

internal suspend fun WorkspaceController.submitBatchImprove(text: String, instruction: String, model: String): Boolean =
    runBatchSubmit { token -> app.batchRunner.submitImprove(text, instruction, model, thinking, useAudioForImprove, token) }
