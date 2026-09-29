package com.minashin1120.voxcribe.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.ai.Prompts
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import kotlinx.coroutines.launch

// 再分析・AI改善・間隔修正・言い直し修正・コピー・音声削除・一括削除。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= 再分析・改善・後処理 =================

fun WorkspaceController.reanalyze() {
    scope.launch {
        val (proceed, useModel) = ensureApiKeyForModel(model, false, null, null)
        if (!proceed) return@launch
        status = StatusView("再分析中...")
        isAppendMode = false
        startTask(AiRequest.Reanalyze(useModel, thinking, rephrase, filler), BeginOn.RESPONSE)
    }
}

fun WorkspaceController.improve() {
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

fun WorkspaceController.fixSpacing() {
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

fun WorkspaceController.correctRephrase() {
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

fun WorkspaceController.copyResult() {
    copyToClipboard(resultText)
    toast.show("コピーしました")
}

fun WorkspaceController.copyToClipboard(text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Voxcribe", text))
}

fun WorkspaceController.deleteAudio() {
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

fun WorkspaceController.requestResetAll() {
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

internal fun WorkspaceController.clearResultUiForNew() {
    resultText = ""
    thoughtText = ""
    initText = ""
    copyEnabled = false
    reanalyzeEnabled = false
    deleteAudioEnabled = false
    fixSpacingEnabled = false
    correctRephraseEnabled = false
}
