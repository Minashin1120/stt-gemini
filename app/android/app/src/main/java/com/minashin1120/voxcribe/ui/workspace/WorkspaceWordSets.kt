package com.minashin1120.voxcribe.ui.workspace

import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 単語セットの有効化・管理・読み仮名生成。
// WorkspaceController の拡張関数。状態は WorkspaceController.kt に持つ（internal 公開）。

// ================= 単語セット =================

fun WorkspaceController.loadWordSetStatus() {
    scope.launch {
        val sets = withContext(Dispatchers.IO) { app.db.wordSets() }
        wordSets.clear()
        wordSets.addAll(sets)
    }
}

val WorkspaceController.activeSetNames: List<String> get() = wordSets.filter { it.isActive }.map { it.name }

fun WorkspaceController.toggleWordSet(id: Long) {
    val r = app.db.toggleWordSet(id)
    if (r != null) {
        toast.show("単語セットの有効/無効を切り替えました")
        loadWordSetStatus()
    } else toast.show("切り替え失敗", true)
}

fun WorkspaceController.resetWordSets() {
    app.db.resetWordSets()
    toast.show("すべてのセットを無効化しました")
    loadWordSetStatus()
}

fun WorkspaceController.openWordSetManage(selectId: Long? = null) {
    wordSetModalOpen = false
    wordSetManageOpen = true
    refreshManageList(selectId)
}

fun WorkspaceController.backToWordSetModal() {
    wordSetManageOpen = false
    wordSetModalOpen = true
    loadWordSetStatus()
}

fun WorkspaceController.refreshManageList(selectId: Long?) {
    manageSelectedId = selectId
    loadWordSetStatus()
    manageWords.clear()
    if (selectId != null) manageWords.addAll(app.db.words(selectId))
}

fun WorkspaceController.createWordSet(name: String) {
    val n = name.trim().take(100).ifEmpty { "新セット" }
    val id = app.db.createWordSet(n)
    refreshManageList(id)
}

fun WorkspaceController.requestDeleteWordSet(id: Long) {
    confirm = ConfirmRequest("このセットを削除しますか？") {
        app.db.deleteWordSet(id)
        refreshManageList(null)
    }
}

fun WorkspaceController.addWord(setId: Long, reading: String, replacement: String): Boolean {
    val r = reading.trim().take(255)
    val p = replacement.trim().take(255)
    if (r.isEmpty() || p.isEmpty()) return false
    app.db.addWord(setId, r, p)
    refreshManageList(setId)
    return true
}

fun WorkspaceController.deleteWord(id: Long) {
    app.db.deleteWord(id)
    refreshManageList(manageSelectedId)
}

suspend fun WorkspaceController.generateYomigana(word: String): String? = withContext(Dispatchers.IO) {
    try {
        app.runner.yomigana(word, prefs.yomiganaModel)
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { toast.show("読みの自動生成に失敗しました: ${e.message ?: ""}", true) }
        null
    }
}
