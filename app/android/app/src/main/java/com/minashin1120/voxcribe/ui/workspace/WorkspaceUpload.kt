package com.minashin1120.voxcribe.ui.workspace

import android.net.Uri
import android.provider.OpenableColumns
import com.minashin1120.voxcribe.ai.AiRequest
import com.minashin1120.voxcribe.data.AudioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ファイル選択とアップロード（index.html の uploadFile / handleFileSelect 相当）。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= アップロード =================

fun WorkspaceController.onFilePicked(uri: Uri) {
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

fun WorkspaceController.removeSelectedFile() {
    selectedFile = null
    uploadButtonsEnabled = false
    upload = UploadPanel()
    errorDownloadVisible = false
    lastLocalAudio = null
    lastLocalAudioName = null
    status = StatusView("待機中")
}

internal fun WorkspaceController.showUploadProgress(percent: Int, label: String) {
    if (!uploadUiActive) return
    val pct = percent.coerceIn(0, 100)
    upload = upload.copy(visible = true, label = label, percent = pct, isError = false, errorPercentText = null, cancelVisible = !uploadCancelled, errorActions = false)
    status = StatusView("$label $pct%")
}

internal fun WorkspaceController.showUploadError(message: String) {
    upload = upload.copy(visible = true, isError = true, label = message, errorPercentText = if (upload.percent > 0) "${upload.percent}%" else "失敗", cancelVisible = false, errorActions = lastLocalAudio != null)
    status = StatusView(message)
    errorDownloadVisible = lastLocalAudio != null
}

fun WorkspaceController.uploadFile(append: Boolean) {
    val sel = selectedFile ?: return
    scope.launch {
        val (proceed, useModel) = ensureApiKeyForModel(model, false, null, null)
        if (!proceed) {
            status = StatusView("待機中")
            return@launch
        }
        if (isBatchFor(useModel)) {
            val batchMime = AudioStore.MIME_BY_EXT[AudioStore.extOf(sel.name)]
            if (batchMime == null) {
                toast.show("対応していない音声形式です", true)
                return@launch
            }
            submitBatchUpload(sel, batchMime, useModel)
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

fun WorkspaceController.cancelUpload() {
    uploadCancelled = true
    upload = upload.copy(cancelVisible = false)
    currentToken?.cancel()
    userAborted = true
}
