package com.minashin1120.voxcribe.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.minashin1120.voxcribe.VoxcribeApp

/** 再コピーは画面もフォアグラウンドサービスも開かず、その場で実行する。 */
class ToolbarCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecordingToolbar.COPY) return
        try {
            VoxcribeApp.instance.toolbar.copy()
        } catch (_: Exception) {
            Toast.makeText(context, "クリップボードにコピーできませんでした", Toast.LENGTH_LONG).show()
        }
    }
}
