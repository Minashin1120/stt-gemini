package com.minashin1120.voxcribe.ai

import com.minashin1120.voxcribe.data.AudioStore
import com.minashin1120.voxcribe.data.Db
import com.minashin1120.voxcribe.data.Prefs
import com.minashin1120.voxcribe.data.SecretStore
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Web版 SSE の {type, content} に相当するイベント */
sealed class AiEvent {
    data class Status(val content: String) : AiEvent()
    data class Thought(val delta: String) : AiEvent()
    data class Text(val delta: String) : AiEvent()
    /** STT 系で最終結果（単語置換後）が差分と一致しない場合の全文置換 */
    data class ReplaceText(val full: String) : AiEvent()
    data class UploadProgress(val sent: Long, val total: Long) : AiEvent()
    data object Done : AiEvent()
    data class Error(val content: String) : AiEvent()
    data class Cancelled(val content: String = "処理を停止しました") : AiEvent()
}

sealed class AiRequest {
    data class Transcribe(
        val file: File,
        val mime: String,
        val model: String,
        val thinkingLevel: String,
        val rephrase: Boolean,
        val filler: Boolean,
        val append: Boolean,
    ) : AiRequest()

    data class Reanalyze(val model: String, val thinkingLevel: String, val rephrase: Boolean, val filler: Boolean) : AiRequest()

    data class Improve(val text: String, val instruction: String, val model: String, val thinkingLevel: String, val useAudio: Boolean) : AiRequest()

    data class CorrectRephrase(val text: String, val model: String, val thinkingLevel: String) : AiRequest()
}

/**
 * Web版サーバー（app.py）の処理を端末内で行うクラス。
 * プロンプト組み立て・コンテキスト注入・履歴保存・単語置換の規則は app.py と同一。
 */
class AiRunner(
    private val db: Db,
    private val prefs: Prefs,
    private val secrets: SecretStore,
    private val audio: AudioStore,
) {
    fun run(req: AiRequest, token: CancelToken, emit: (AiEvent) -> Unit) {
        try {
            when (req) {
                is AiRequest.Transcribe -> transcribe(req, token, emit)
                is AiRequest.Reanalyze -> reanalyze(req, token, emit)
                is AiRequest.Improve -> improve(req, token, emit)
                is AiRequest.CorrectRephrase -> correctRephrase(req, token, emit)
            }
        } catch (e: CancelledException) {
            emit(AiEvent.Cancelled())
        } catch (e: AiException) {
            if (token.isCancelled) emit(AiEvent.Cancelled()) else emit(AiEvent.Error(e.message ?: "Unknown error"))
        } catch (e: Exception) {
            if (token.isCancelled) emit(AiEvent.Cancelled()) else emit(AiEvent.Error("処理中にエラーが発生しました"))
        }
    }

    // ---------- /transcribe ----------
    private fun transcribe(req: AiRequest.Transcribe, token: CancelToken, emit: (AiEvent) -> Unit) {
        val model = Models.validate(req.model)
        if (req.file.length() > Models.maxBytes(model)) throw AiException("音声ファイルが上限サイズを超えています")
        val apiKey = requireKey(model)
        // 新規（追加でない）場合は履歴と保存音声をすべて削除してから開始（app.py と同じ）
        if (!req.append) {
            db.clearHistory()
            audio.deleteAllExcept(req.file)
            prefs.lastAudioFile = null
            prefs.lastAudioMime = null
        }
        prefs.lastAudioFile = req.file.name
        prefs.lastAudioMime = req.mime
        runStt(model, apiKey, req.file, req.mime, req.thinkingLevel, token, emit, "transcribe", "Audio Input") {
            Prompts.transcription(
                historyContext(), wordListContext(), Prompts.TRANSCRIBE_MODE_LABEL,
                req.rephrase, req.filler, Models.isLite(model)
            )
        }
    }

    // ---------- /reanalyze ----------
    private fun reanalyze(req: AiRequest.Reanalyze, token: CancelToken, emit: (AiEvent) -> Unit) {
        val name = prefs.lastAudioFile ?: throw AiException("ファイルなし")
        val file = audio.resolve(name) ?: throw AiException("期限切れ")
        val model = Models.validate(req.model)
        val apiKey = requireKey(model)
        val mime = prefs.lastAudioMime ?: "audio/mpeg"
        runStt(model, apiKey, file, mime, req.thinkingLevel, token, emit, "reanalyze", "Re-analysis Request") {
            Prompts.reanalyze(historyContext(), wordListContext(), req.rephrase, req.filler, Models.isLite(model))
        }
    }

    private fun runStt(
        model: String,
        apiKey: String,
        file: File,
        mime: String,
        thinkingLevel: String,
        token: CancelToken,
        emit: (AiEvent) -> Unit,
        action: String,
        summary: String,
        prompt: () -> String,
    ) {
        val status: (String) -> Unit = { emit(AiEvent.Status(it)) }
        val progress: (Long, Long) -> Unit = { s, t -> emit(AiEvent.UploadProgress(s, t)) }
        when {
            Models.isGrok(model) -> {
                var text = GrokClient.transcribe(apiKey, file, token, status, progress)
                text = applyWordReplacements(text)
                if (token.isCancelled) throw CancelledException()
                emit(AiEvent.ReplaceText(text))
                emit(AiEvent.Done)
                saveHistory(action, summary, "", text)
            }
            model == "gpt-transcribe" || model == "gpt-live-transcribe" -> {
                val raw = if (model == "gpt-transcribe")
                    OpenAiClient.transcribe(apiKey, file, token, status, { emit(AiEvent.Text(it)) }, progress)
                else
                    OpenAiClient.liveTranscribe(apiKey, file, token, status) { emit(AiEvent.Text(it)) }
                val text = applyWordReplacements(raw)
                if (token.isCancelled) throw CancelledException()
                emit(AiEvent.ReplaceText(text))
                emit(AiEvent.Done)
                saveHistory(action, summary, "", text)
            }
            else -> {
                val parts = listOf(GeminiPart.Text(prompt()), GeminiPart.Audio(file, mime))
                val (thought, text) = GeminiClient.stream(
                    apiKey, model, parts, Models.apiThinkingLevel(thinkingLevel), token, status,
                    { emit(AiEvent.Thought(it)) }, { emit(AiEvent.Text(it)) }, progress
                )
                emit(AiEvent.Done)
                saveHistory(action, summary, thought, text)
            }
        }
    }

    // ---------- /improve ----------
    private fun improve(req: AiRequest.Improve, token: CancelToken, emit: (AiEvent) -> Unit) {
        if (req.text.isEmpty() || req.instruction.isEmpty()) throw AiException("テキストと指示を入力してください")
        if (req.text.length > 200_000 || req.instruction.length > 20_000) throw AiException("入力が長すぎます")
        val apiKey = secrets.get(KeyType.GEMINI) ?: throw AiException("API Key not set")
        // 最新履歴の結果を手動修正後テキストで上書き（app.py と同じ）
        db.latestHistory()?.let { db.updateHistoryResult(it.id, req.text) }
        val parts = mutableListOf<GeminiPart>(
            GeminiPart.Text(Prompts.improve(historyContext(), wordListContext(), req.text, req.instruction))
        )
        if (req.useAudio) {
            audio.resolve(prefs.lastAudioFile)?.let {
                parts += GeminiPart.Text("Reference Audio:")
                parts += GeminiPart.Audio(it, prefs.lastAudioMime ?: "audio/mp3")
            }
        }
        var model = Models.validate(req.model)
        if (Models.isStt(model)) model = Models.FALLBACK
        val (thought, text) = GeminiClient.stream(
            apiKey, model, parts, Models.apiThinkingLevel(req.thinkingLevel), token,
            { emit(AiEvent.Status(it)) }, { emit(AiEvent.Thought(it)) }, { emit(AiEvent.Text(it)) }
        )
        emit(AiEvent.Done)
        saveHistory("improve", req.instruction, thought, text)
    }

    // ---------- /correct_rephrase ----------
    private fun correctRephrase(req: AiRequest.CorrectRephrase, token: CancelToken, emit: (AiEvent) -> Unit) {
        if (req.text.isEmpty()) throw AiException("テキストを入力してください")
        if (req.text.length > 200_000) throw AiException("入力が長すぎます")
        val apiKey = secrets.get(KeyType.GEMINI) ?: throw AiException("API Key not set")
        var model = Models.validate(req.model)
        if (Models.isStt(model)) model = Models.FALLBACK
        val parts = listOf(GeminiPart.Text(Prompts.TEXT_REPHRASE_CORRECTION_PROMPT + req.text))
        val (thought, text) = GeminiClient.stream(
            apiKey, model, parts, Models.apiThinkingLevel(req.thinkingLevel), token,
            { emit(AiEvent.Status(it)) }, { emit(AiEvent.Thought(it)) }, { emit(AiEvent.Text(it)) }
        )
        emit(AiEvent.Done)
        saveHistory("correct_rephrase", "Rephrase correction (text only)", thought, text)
    }

    // ---------- /api/yomigana/generate ----------
    fun yomigana(word: String, requestedModel: String): String {
        val model = if (Models.GEMINI.any { it.value == requestedModel }) requestedModel else Models.FALLBACK
        val apiKey = secrets.get(KeyType.GEMINI) ?: throw AiException("Gemini API key not configured")
        return GeminiClient.yomigana(apiKey, model, word)
    }

    private fun requireKey(model: String): String {
        val type = Models.keyType(model)
        return secrets.get(type) ?: throw AiException(
            when (type) {
                KeyType.XAI -> "xAI API Key not set. Go to Settings to configure it."
                KeyType.OPENAI -> "OpenAI API Key not set. Go to Settings to configure it."
                KeyType.GEMINI -> "API Key not set"
            }
        )
    }

    /** app.py save_history: thought と result が両方空なら保存しない */
    private fun saveHistory(action: String, input: String, thought: String, result: String) {
        if (thought.isEmpty() && result.isEmpty()) return
        db.insertHistory(action, input, thought, result)
    }

    /** app.py get_active_history_context */
    fun historyContext(): String {
        val retentionMs = prefs.retentionMinutes * 60_000L
        val last = db.latestHistory() ?: return ""
        val now = System.currentTimeMillis()
        if (now - last.timestampMs > retentionMs) return ""
        val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("\n--- CONTEXT: PREVIOUS INTERACTION HISTORY ---\n")
        for (h in db.historySince(now - retentionMs, newestFirst = false)) {
            sb.append("[Action: ${h.actionType}] (${utc.format(Date(h.timestampMs))})\n")
            if (h.inputSummary.isNotEmpty()) sb.append("Input/Instruction: ${h.inputSummary}\n")
            if (h.thoughtText.isNotEmpty()) sb.append("Model Thought: ${h.thoughtText}\n")
            if (h.resultText.isNotEmpty()) sb.append("Model Output: ${h.resultText}\n")
            sb.append("---------------------------------------------\n")
        }
        val files = audio.dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        if (files.isNotEmpty()) {
            val local = SimpleDateFormat("HH:mm:ss", Locale.US)
            sb.append("\n--- SAVED DATA (AVAILABLE AUDIO FILES) ---\n")
            for (f in files) sb.append("- Saved Audio: ${f.name} (Uploaded: ${local.format(Date(f.lastModified()))})\n")
            sb.append("------------------------------------------\n")
        }
        return sb.toString()
    }

    /** app.py get_word_list_context */
    fun wordListContext(): String {
        val active = db.wordSets().filter { it.isActive }
        if (active.isEmpty()) return ""
        val sb = StringBuilder("\n--- CUSTOM VOCABULARY (READING -> REPLACEMENT) ---\n")
        sb.append("If you hear something similar to the reading on the left, strictly use the word on the right.\n")
        for (s in active) for (w in db.words(s.id)) sb.append("- ${w.reading} -> ${w.replacement}\n")
        sb.append("--------------------------------------------------\n")
        return sb.toString()
    }

    /** Grok / OpenAI 結果への単語置換（大文字小文字無視のリテラル置換を順に適用） */
    fun applyWordReplacements(input: String): String {
        var text = input
        for (w in db.activeWords()) {
            if (w.reading.isEmpty() || w.replacement.isEmpty()) continue
            text = Regex(Regex.escape(w.reading), RegexOption.IGNORE_CASE).replace(text, Regex.escapeReplacement(w.replacement))
        }
        return text
    }
}
