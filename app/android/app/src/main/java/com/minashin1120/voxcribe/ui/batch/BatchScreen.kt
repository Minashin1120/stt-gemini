package com.minashin1120.voxcribe.ui.batch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.data.BatchRow
import com.minashin1120.voxcribe.ui.common.AlertKind
import com.minashin1120.voxcribe.ui.common.AppBackground
import com.minashin1120.voxcribe.ui.common.AppCard
import com.minashin1120.voxcribe.ui.common.AppModal
import com.minashin1120.voxcribe.ui.common.AppNavbar
import com.minashin1120.voxcribe.ui.common.BsButton
import com.minashin1120.voxcribe.ui.common.BtnSize
import com.minashin1120.voxcribe.ui.common.BtnVariant
import com.minashin1120.voxcribe.ui.common.CardHeader
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import com.minashin1120.voxcribe.ui.common.Divider
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.theme.Bs
import com.minashin1120.voxcribe.ui.theme.T
import com.minashin1120.voxcribe.ui.workspace.WorkspaceController
import com.minashin1120.voxcribe.ui.workspace.answerBatchPrompt
import com.minashin1120.voxcribe.ui.workspace.cancelBatch
import com.minashin1120.voxcribe.ui.workspace.deleteBatch
import com.minashin1120.voxcribe.ui.workspace.importBatch
import com.minashin1120.voxcribe.ui.workspace.loadBatches
import com.minashin1120.voxcribe.ui.workspace.refreshBatches
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun statusLabel(s: String) = when (s) {
    "running" -> "処理中"
    "succeeded" -> "完了"
    "failed" -> "失敗"
    "cancelled" -> "取消"
    "expired" -> "期限切れ"
    else -> s
}

private fun actionLabel(a: String) = when (a) {
    "transcribe" -> "文字起こし"
    "reanalyze" -> "再分析"
    "improve" -> "改善"
    else -> a
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** batch.html の移植（Gemini Batch API のジョブ一覧。完了したジョブは「取り込む」で履歴と結果欄へ反映） */
@Composable
fun BatchScreen(onNavigate: (String) -> Unit) {
    val ctrl = VoxcribeApp.instance.workspace
    val t = T.c
    LaunchedEffect(Unit) {
        ctrl.loadBatches()
        ctrl.refreshBatches()
    }
    AppBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppNavbar("batch", onNavigate)
            Column(Modifier.padding(top = 8.dp)) {
                Text("BATCH", color = t.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
                Text("Batch処理", color = t.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
                Text(
                    "Gemini Batch API（通常料金の50%・最大24時間）に投入したジョブの一覧です。完了したジョブは取り込みで履歴と結果欄に反映できます。",
                    color = t.muted, fontSize = 15.sp
                )
            }
            AppCard {
                CardHeader {
                    Text("ジョブ一覧", color = t.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    BsButton("更新", { ctrl.refreshBatches() }, variant = BtnVariant.OUTLINE_SECONDARY, size = BtnSize.SM, icon = Icons.Outlined.Refresh)
                }
                if (ctrl.batches.isEmpty()) {
                    Text("Batchジョブはありません", color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                } else {
                    ctrl.batches.forEachIndexed { i, job ->
                        if (i > 0) Divider()
                        BatchItem(ctrl, job)
                    }
                }
            }
            MutedText(
                "対応モデル: Gemini 3.8 / 3.7 / 3.6 / 3.5 Flash、3.5 Flash-Lite、3 Flash Preview、3.1 Flash-Lite。" +
                    "Transcribe・Live・OpenAI・Grok には公式のBatch APIがないため対象外です。ワークスペースでGeminiモデルを選び「Batchで実行」をオンにすると投入できます。",
                fontSize = 12.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BatchItem(ctrl: WorkspaceController, job: BatchRow) {
    val t = T.c
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val kind = when (job.status) {
                "running" -> AlertKind.INFO
                "succeeded" -> AlertKind.SUCCESS
                "failed" -> AlertKind.DANGER
                else -> null
            }
            if (kind != null) Pill(statusLabel(job.status), kind.bg, kind.fg)
            else Pill(statusLabel(job.status), Bs.secondary.copy(alpha = .15f), Bs.secondary)
            if (job.status == "succeeded") {
                if (job.imported) Pill("取り込み済み", Bs.secondary.copy(alpha = .15f), Bs.secondary)
                else Pill("未取り込み", AlertKind.WARNING.bg, AlertKind.WARNING.fg)
            }
            Text(actionLabel(job.actionType), color = t.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(job.model, color = t.muted, fontSize = 12.sp)
        Text(
            formatTime(job.createdMs) + (if (job.completedMs > 0) " → " + formatTime(job.completedMs) else "") +
                (if (job.inputSummary.isNotEmpty()) " ／ " + job.inputSummary else ""),
            color = t.muted, fontSize = 12.sp
        )
        if (job.error.isNotEmpty()) Text(job.error, color = Bs.danger, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.status == "succeeded") {
                BsButton(if (job.imported) "再取り込み" else "取り込む", { ctrl.importBatch(job.id) }, size = BtnSize.SM)
            }
            if (job.status == "running") {
                BsButton("取消", { ctrl.cancelBatch(job.id) }, variant = BtnVariant.OUTLINE_WARNING, size = BtnSize.SM)
            } else {
                BsButton(null, {
                    ctrl.confirm = ConfirmRequest("このBatchジョブを削除しますか？") { ctrl.deleteBatch(job.id) }
                }, variant = BtnVariant.OUTLINE_DANGER, size = BtnSize.SM, icon = Icons.Outlined.Delete)
            }
        }
    }
}

/** 完了時の「取り込む / 後で」確認ダイアログ（後で選んでも Batch 画面の一覧から取り込める） */
@Composable
fun BatchDoneModal(job: BatchRow, ctrl: WorkspaceController) {
    val t = T.c
    AppModal(
        "Batch処理が完了しました", onDismiss = { ctrl.answerBatchPrompt(false) }, titleIcon = Icons.Outlined.CheckCircle,
        footer = {
            BsButton("後で", { ctrl.answerBatchPrompt(false) }, variant = BtnVariant.SECONDARY)
            BsButton("取り込む", { ctrl.answerBatchPrompt(true) })
        }
    ) {
        Text("${actionLabel(job.actionType)}（${job.model}）／ ${formatTime(job.createdMs)}", color = t.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("結果を今すぐ取り込みますか？", color = t.text)
        MutedText("「後で」を選んでも、Batch画面の一覧からいつでも取り込めます。", fontSize = 12.sp)
    }
}
