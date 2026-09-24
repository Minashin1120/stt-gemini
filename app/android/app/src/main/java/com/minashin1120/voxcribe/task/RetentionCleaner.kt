package com.minashin1120.voxcribe.task

import com.minashin1120.voxcribe.VoxcribeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Web版 cleanup_old_data 相当: 約60秒ごとに保持時間を過ぎた履歴・音声を削除 */
object RetentionCleaner {
    fun start(app: VoxcribeApp) {
        app.appScope.launch {
            while (true) {
                withContext(Dispatchers.IO) { runOnce(app) }
                delay(60_000)
            }
        }
    }

    fun runOnce(app: VoxcribeApp) {
        try {
            val cutoff = System.currentTimeMillis() - app.prefs.retentionMinutes * 60_000L
            app.db.deleteHistoryOlderThan(cutoff)
            app.audio.deleteOlderThan(cutoff)
            app.prefs.lastAudioFile?.let { if (app.audio.resolve(it) == null) app.prefs.lastAudioFile = null }
        } catch (_: Exception) {
        }
    }
}
