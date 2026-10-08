package com.minashin1120.voxcribe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.ui.common.CustomSelect
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.common.SwitchRow

@Composable
internal fun ToolbarSettings() {
    val app = VoxcribeApp.instance
    val prefs = app.prefs
    var enabled by remember { mutableStateOf(prefs.toolbarEnabled) }
    var model by remember { mutableStateOf(prefs.toolbarModel) }
    var thinking by remember { mutableStateOf(prefs.toolbarThinking) }
    var rephrase by remember { mutableStateOf(prefs.toolbarRephrase) }
    var filler by remember { mutableStateOf(prefs.toolbarFiller) }
    var noise by remember { mutableStateOf(prefs.toolbarNoise) }
    var format by remember { mutableStateOf(prefs.toolbarFormat) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.toolbar.refresh()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            app.toaster.show("通知を表示するには、端末の設定でVoxcribeの通知を許可してください", true)
        }
    }
    SettingsCard(Icons.Outlined.Mic, "通知の録音ツールバー") {
        SwitchRow("常時通知を表示", enabled, {
            enabled = it; prefs.toolbarEnabled = it
            app.toolbar.refresh()
            if (it) permissions.launch(buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        })
        MutedText("停止すると文字起こしをクリップボードにコピーします。履歴・結果ボックス・保存音声には追加しません。設定は変更すると保存され、次の録音から適用されます。")
        Spacer(Modifier.height(12.dp))
        FieldLabel("通知用モデル")
        CustomSelect(Models.ALL.map { it.value to it.label }, model, { model = it; prefs.toolbarModel = it })
        if (!Models.isStt(model)) {
            Spacer(Modifier.height(12.dp))
            FieldLabel("推論レベル")
            CustomSelect(Models.THINKING.map { it.value to it.label }, thinking, { thinking = it; prefs.toolbarThinking = it })
        }
        SwitchRow("言い直し修正を許可", rephrase, { rephrase = it; prefs.toolbarRephrase = it }, enabled = !Models.isStt(model))
        SwitchRow("フィラー除去", filler, { filler = it; prefs.toolbarFiller = it }, enabled = !Models.isStt(model))
        if (Models.isStt(model)) MutedText("専用文字起こしモデルでは言い直し修正・フィラー除去の指示を指定できません。これらを指定する場合は通常のGeminiモデルを選んでください。")
        SwitchRow("ノイズ除去", noise, { noise = it; prefs.toolbarNoise = it })
        FieldLabel("音声形式")
        CustomSelect(listOf("mp3" to "MP3 (192kbps)", "wav" to "WAV (PCM)"), format, { format = it; prefs.toolbarFormat = it })
        Spacer(Modifier.height(12.dp))
        MutedText("「停止してアプリで継続」は、それまでの音声を保持して一時停止します。アプリで再開し、停止すると全体をコピーできます。コピーした結果は次の録音まで通知から再コピーできます。")
    }
}
