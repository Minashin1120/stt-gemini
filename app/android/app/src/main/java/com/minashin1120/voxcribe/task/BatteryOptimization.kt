package com.minashin1120.voxcribe.task

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.minashin1120.voxcribe.VoxcribeApp

/** アプリを操作している時だけ最適化除外を案内する。通知アクションからは表示しない。 */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun offer(context: Context, force: Boolean = false) {
        val app = VoxcribeApp.instance
        if (!force && app.prefs.getString("toolbar_battery_prompt_seen") == "true") return
        val activity = findActivity(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        app.prefs.putString("toolbar_battery_prompt_seen", "true")
        if (isExempt(context)) return
        AlertDialog.Builder(activity)
            .setTitle("常駐通知・録音を維持するための設定")
            .setMessage("Voxcribeをバッテリー最適化の対象外にすると、バックグラウンドでの録音・文字起こしが省電力機能で中断されにくくなります。次の画面で最適化の除外を許可してください。バッテリー消費が増える場合があります。")
            .setPositiveButton("対象外に設定") { _, _ -> requestExemption(activity) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun requestExemption(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")))
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(activity, "端末の設定からVoxcribeのバッテリー最適化を無効にしてください", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
