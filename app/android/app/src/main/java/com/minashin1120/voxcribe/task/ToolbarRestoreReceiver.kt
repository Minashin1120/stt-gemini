package com.minashin1120.voxcribe.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.minashin1120.voxcribe.VoxcribeApp

/** 再起動・アプリ更新後は待機通知だけを復元し、録音は自動開始しない。 */
class ToolbarRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            VoxcribeApp.instance.toolbar.refresh()
        }
    }
}
