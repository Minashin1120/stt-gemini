package com.minashin1120.voxcribe.ai

import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.BatchRow
import com.minashin1120.voxcribe.data.Db
import com.minashin1120.voxcribe.data.Prefs
import com.minashin1120.voxcribe.data.SecretStore
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File

/**
 * Gemini Batch API ジョブの投入・状態更新・取り込み（Web版 app/batch.py + routes_batch.py に相当）。
 * 結果の取り込みは履歴へ追加するだけで、既存の履歴・保存音声は消さない。
 * 同期ブロッキングなので呼び出し側は Dispatchers.IO で実行すること。
 */
class BatchRunner(
    private val db: Db,
    private val prefs: Prefs,
    private val secrets: SecretStore,
    private val audio: AudioStore,
    private val runner: AiRunner,
) {
    companion object {
        const val MAX_RUNNING_JOBS = 20
    }

    private fun requireBatchKey(model: String): String {
        if (!Models.supportsBatch(model)) throw AiException("このモデルはBatch処理に対応していません（Gemini 通常モデルのみ対応）")
        return secrets.get(KeyType.GEMINI) ?: throw AiException("API Key not set")
    }

    private fun checkRunningLimit() {
        if (db.batches().count { it.status == "running" } >= MAX_RUNNING_JOBS) {
            throw AiException("進行中のBatchが上限(${MAX_RUNNING_JOBS}件)に達しています")
        }
    }

    private fun uploadAudio(apiKey: String, file: File, mime: String, token: CancelToken, onProgress: (Long, Long) -> Unit): JSONObject {
        val body = ProgressFileBody(file, mime.toMediaType(), onProgress)
        val uploaded = BatchClient.uploadFile(apiKey, body, file.length(), mime, file.name, token)
        return BatchClient.audioPart(BatchClient.waitActive(apiKey, uploaded, token), mime)
    }

    private fun create(apiKey: String, model: String, action: String, summary: String, parts: List<JSONObject>, thinking: String, token: CancelToken): Long {
        val name = BatchClient.createBatch(apiKey, model, parts, Models.apiThinkingLevel(thinking), "voxcribe-$action", token)
        return db.insertBatch(name, model, action, summary)
    }

    /** 文字起こしをBatchへ投入する。履歴・保存音声は消さない */
    fun submitTranscribe(
        file: File, mime: String, model: String, thinking: String, rephrase: Boolean, filler: Boolean,
        token: CancelToken, onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val m = Models.validate(model)
        val apiKey = requireBatchKey(m)
        if (file.length() > Models.maxBytes(m)) throw AiException("音声ファイルが上限サイズを超えています")
        checkRunningLimit()
        val prompt = Prompts.transcription(
            runner.historyContext(), runner.wordListContext(), Prompts.TRANSCRIBE_MODE_LABEL,
            rephrase, filler, Models.isLite(m)
        )
        val parts = listOf(BatchClient.textPart(prompt), uploadAudio(apiKey, file, mime, token, onProgress))
        return create(apiKey, m, "transcribe", "Audio Input (Batch)", parts, thinking, token)
    }

    fun submitReanalyze(model: String, thinking: String, rephrase: Boolean, filler: Boolean, token: CancelToken): Long {
        val m = Models.validate(model)
        val apiKey = requireBatchKey(m)
        val name = prefs.lastAudioFile ?: throw AiException("ファイルなし")
        val file = audio.resolve(name) ?: throw AiException("期限切れ")
        checkRunningLimit()
        val mime = prefs.lastAudioMime ?: "audio/mpeg"
        val prompt = Prompts.reanalyze(runner.historyContext(), runner.wordListContext(), rephrase, filler, Models.isLite(m))
        val parts = listOf(BatchClient.textPart(prompt), uploadAudio(apiKey, file, mime, token) { _, _ -> })
        return create(apiKey, m, "reanalyze", "Re-analysis Request (Batch)", parts, thinking, token)
    }

    fun submitImprove(text: String, instruction: String, model: String, thinking: String, useAudio: Boolean, token: CancelToken): Long {
        if (text.isEmpty() || instruction.isEmpty()) throw AiException("テキストと指示を入力してください")
        if (text.length > 200_000 || instruction.length > 20_000) throw AiException("入力が長すぎます")
        val m = Models.validate(model)
        val apiKey = requireBatchKey(m)
        checkRunningLimit()
        // 通常の改善と同様、手動修正を最新の履歴に反映してから文脈を作る
        db.latestHistory()?.let { db.updateHistoryResult(it.id, text) }
        val parts = mutableListOf(BatchClient.textPart(Prompts.improve(runner.historyContext(), runner.wordListContext(), text, instruction)))
        if (useAudio) {
            audio.resolve(prefs.lastAudioFile)?.let {
                parts += BatchClient.textPart("Reference Audio:")
                parts += uploadAudio(apiKey, it, prefs.lastAudioMime ?: "audio/mp3", token) { _, _ -> }
            }
        }
        return create(apiKey, m, "improve", instruction, parts, thinking, token)
    }

    /** 進行中ジョブを問い合わせて更新する。今回新たに完了（succeeded）したジョブを返す */
    fun refreshAll(): List<BatchRow> {
        val apiKey = secrets.get(KeyType.GEMINI) ?: return emptyList()
        val newlyDone = ArrayList<BatchRow>()
        for (job in db.batches().filter { it.status == "running" }) {
            try {
                val st = BatchClient.fetchState(apiKey, job.providerJob)
                when (st.status) {
                    "running" -> Unit
                    "succeeded" -> {
                        val file = st.responsesFile
                        if (file == null) {
                            db.finishBatch(job.id, "failed", "", "", "結果ファイルが見つかりません")
                        } else {
                            val r = BatchClient.parseResults(BatchClient.downloadResults(apiKey, file))
                            if (r.text.isNotEmpty() || r.thought.isNotEmpty()) {
                                db.finishBatch(job.id, "succeeded", r.thought, r.text, "")
                                db.batch(job.id)?.let { newlyDone += it }
                            } else {
                                db.finishBatch(job.id, "failed", "", "", r.error.ifEmpty { "結果が空でした" })
                            }
                        }
                    }
                    else -> db.finishBatch(job.id, st.status, "", "", st.error)
                }
            } catch (_: Exception) {
                // 通信エラーなどは次回の更新で再試行する（状態は変えない）
            }
        }
        return newlyDone
    }

    /** 結果を履歴へ追加する（既存履歴は消さない）。取り込んだジョブを返す */
    fun importJob(id: Long): BatchRow? {
        val job = db.batch(id) ?: return null
        if (job.status != "succeeded") throw AiException("完了していないジョブは取り込めません")
        if (job.thoughtText.isNotEmpty() || job.resultText.isNotEmpty()) {
            db.insertHistory(job.actionType, job.inputSummary.ifEmpty { "Batch" }, job.thoughtText, job.resultText)
        }
        db.markBatchImported(id)
        return db.batch(id)
    }

    fun cancel(id: Long) {
        val job = db.batch(id) ?: return
        if (job.status != "running") return
        secrets.get(KeyType.GEMINI)?.let { BatchClient.cancel(it, job.providerJob) }
        db.finishBatch(id, "cancelled", "", "", "")
    }

    fun delete(id: Long) {
        db.deleteBatch(id)
    }
}
