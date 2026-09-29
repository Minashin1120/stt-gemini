package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.data.SecretStore
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.util.Downloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// APIキー未設定モーダル（ensureApiKeyForModel）と全データ削除。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= APIキー確認（ensureApiKeyForModel） =================

internal suspend fun WorkspaceController.ensureApiKeyForModel(m: String, isRecording: Boolean, audioFile: File?, audioName: String?): Pair<Boolean, String> {
    val type = Models.keyType(m)
    if (app.secrets.has(type)) return true to m
    val deferred = CompletableDeferred<Pair<Boolean, String>>()
    apiKeyPrompt = ApiKeyPrompt(type, m, isRecording, audioFile, audioName, result = deferred)
    return deferred.await()
}

fun WorkspaceController.akSave(input: String) {
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
    if (p.keyType == KeyType.XAI && !SecretStore.isPlausibleXaiApiKey(key)) {
        apiKeyPrompt = p.copy(error = "xAI APIキーの形式が正しくありません（xai- で始まるキーを入力してください）")
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

fun WorkspaceController.akShowSwitch() {
    val p = apiKeyPrompt ?: return
    val models = mutableListOf<Pair<String, String>>()
    if (app.secrets.has(KeyType.GEMINI)) models += Models.GEMINI.map { it.value to it.label }
    if (app.secrets.has(KeyType.OPENAI)) models += listOf("gpt-transcribe" to "GPT-Transcribe", "gpt-live-transcribe" to "GPT-Live Transcribe", "whisper-1" to "Whisper", "gpt-4o-transcribe" to "GPT-4o Transcribe", "gpt-4o-mini-transcribe" to "GPT-4o Mini Transcribe", "gpt-4o-transcribe-diarize" to "GPT-4o Speaker Transcribe", "gpt-realtime-whisper" to "GPT-Realtime-Whisper")
    if (app.secrets.has(KeyType.XAI)) models += listOf("grok-stt" to "Grok STT", "grok-live-transcribe" to "Grok Live")
    apiKeyPrompt = p.copy(view = AkView.SWITCH, switchModels = models)
}

fun WorkspaceController.akPickModel(value: String) {
    val p = apiKeyPrompt ?: return
    selectModel(value)
    apiKeyPrompt = null
    p.result.complete(true to value)
}

fun WorkspaceController.akBackToInput() {
    apiKeyPrompt = apiKeyPrompt?.copy(view = AkView.INPUT)
}

fun WorkspaceController.akClose() {
    val p = apiKeyPrompt ?: return
    if (p.isRecording) {
        apiKeyPrompt = p.copy(view = AkView.DISCARD)
    } else {
        apiKeyPrompt = null
        p.result.complete(false to p.model)
    }
}

fun WorkspaceController.akConfirmDiscard() {
    val p = apiKeyPrompt ?: return
    apiKeyPrompt = null
    p.result.complete(false to p.model)
}

fun WorkspaceController.akDownload() {
    val p = apiKeyPrompt ?: return
    val f = p.audioFile ?: return
    scope.launch {
        val name = p.audioName ?: "recording.mp3"
        val ok = withContext(Dispatchers.IO) { Downloads.save(ctx, f, name, Downloads.mimeFor(name)) }
        if (ok) toast.show("音声のダウンロードを開始しました") else toast.show("ダウンロード失敗", true)
    }
}

// ================= 全データ削除（設定画面） =================

fun WorkspaceController.wipeAllData() {
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
