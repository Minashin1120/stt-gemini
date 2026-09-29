package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.data.HistoryRow
import com.minashin1120.voxcribe.data.SavedAudio
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import com.minashin1120.voxcribe.util.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 履歴・保存データ（ファイルマネージャ）・エラー時のローカル音声ダウンロード。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= 履歴 =================

fun WorkspaceController.loadHistory() {
    scope.launch {
        val rows = withContext(Dispatchers.IO) {
            app.db.historySince(System.currentTimeMillis() - prefs.retentionMinutes * 60_000L, newestFirst = true)
        }
        history.clear()
        history.addAll(rows)
        historyLoaded = true
    }
}

fun WorkspaceController.requestDeleteHistory(id: Long) {
    confirm = ConfirmRequest("この履歴を削除しますか？") {
        if (app.db.deleteHistory(id)) {
            toast.show("履歴を削除しました")
            loadHistory()
        } else toast.show("削除失敗", true)
    }
}

fun WorkspaceController.historyTime(row: HistoryRow): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(row.timestampMs))

// ================= 保存データ =================

fun WorkspaceController.openFileManager() {
    fileManagerOpen = true
    loadFileMetadata()
}

fun WorkspaceController.loadFileMetadata() {
    filesLoading = true
    scope.launch {
        val list = withContext(Dispatchers.IO) { app.audio.list() }
        files.clear()
        files.addAll(list)
        filesLoading = false
    }
}

fun WorkspaceController.requestDeleteFile(f: SavedAudio) {
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
fun WorkspaceController.exportFile(f: SavedAudio) {
    toast.show("並列ダウンロード開始 (${maxOf(1, ((f.size + 10L * 1024 * 1024 - 1) / (10L * 1024 * 1024)).toInt())}分割)...")
    scope.launch {
        val ok = withContext(Dispatchers.IO) { Downloads.save(ctx, f.file, f.file.name, Downloads.mimeFor(f.file.name)) }
        if (ok) toast.show("ダウンロード完了") else toast.show("ダウンロード失敗", true)
    }
}

// ================= エラー時の音声ダウンロード =================

internal fun WorkspaceController.rememberLocalAudio(file: File, name: String) {
    lastLocalAudio = file
    lastLocalAudioName = name
}

fun WorkspaceController.downloadLocalAudio() {
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
