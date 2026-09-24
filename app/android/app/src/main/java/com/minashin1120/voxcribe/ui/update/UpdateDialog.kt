package com.minashin1120.voxcribe.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ui.common.AlertBox
import com.minashin1120.voxcribe.ui.common.AlertKind
import com.minashin1120.voxcribe.ui.common.AppModal
import com.minashin1120.voxcribe.ui.common.BsButton
import com.minashin1120.voxcribe.ui.common.BtnVariant
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.theme.T
import java.io.File

/** 起動時にGitHub Releaseで新しいバージョンが見つかったときに表示する（Android版だけの仕組み） */
@Composable
fun UpdateDialog(onInstall: (File) -> Unit) {
    val ctrl = VoxcribeApp.instance.updates
    val info = ctrl.info ?: return
    if (ctrl.dismissed) return
    val downloadedFile = ctrl.downloadedFile

    AppModal(
        "アップデートがあります",
        onDismiss = { ctrl.dismiss() },
        titleIcon = Icons.Outlined.Download,
        footer = {
            BsButton("後で", { ctrl.dismiss() }, variant = BtnVariant.OUTLINE_SECONDARY, enabled = !ctrl.downloading)
            if (downloadedFile != null) {
                BsButton("インストール", { onInstall(downloadedFile) })
            } else {
                BsButton(
                    if (ctrl.downloading) "ダウンロード中..." else "更新する",
                    { ctrl.startDownload() },
                    loading = ctrl.downloading,
                    enabled = !ctrl.downloading,
                )
            }
        },
    ) {
        val t = T.c
        Text("新しいバージョン ${info.tag} が公開されています。", color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        ctrl.error?.let {
            AlertBox(AlertKind.DANGER, Icons.Outlined.WarningAmber, it)
            Spacer(Modifier.height(8.dp))
        }

        if (ctrl.downloading || downloadedFile != null) {
            val pct = if (downloadedFile != null) 1f else ctrl.progress
            MutedText(if (downloadedFile != null) "ダウンロード完了" else "ダウンロード中 ${(pct * 100).toInt()}%")
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(t.softPrimary)) {
                Box(Modifier.fillMaxWidth(pct).height(10.dp).clip(RoundedCornerShape(999.dp)).background(t.primary))
            }
            Spacer(Modifier.height(12.dp))
        }

        if (info.notes.isNotBlank()) {
            Text(info.notes, color = t.muted, fontSize = 13.sp)
        }
    }
}
