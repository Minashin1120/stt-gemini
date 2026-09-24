package com.minashin1120.voxcribe.ui.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.ui.common.AlertBox
import com.minashin1120.voxcribe.ui.common.AlertKind
import com.minashin1120.voxcribe.ui.common.AppBackground
import com.minashin1120.voxcribe.ui.common.AppCard
import com.minashin1120.voxcribe.ui.common.AppNavbar
import com.minashin1120.voxcribe.ui.common.Badge
import com.minashin1120.voxcribe.ui.common.BsButton
import com.minashin1120.voxcribe.ui.common.BtnSize
import com.minashin1120.voxcribe.ui.common.BtnVariant
import com.minashin1120.voxcribe.ui.common.CardHeader
import com.minashin1120.voxcribe.ui.common.CheckRow
import com.minashin1120.voxcribe.ui.common.CustomSelect
import com.minashin1120.voxcribe.ui.common.Divider
import com.minashin1120.voxcribe.ui.common.FormInput
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.common.SectionTitle
import com.minashin1120.voxcribe.ui.common.StatusDot
import com.minashin1120.voxcribe.ui.common.SwitchRow
import com.minashin1120.voxcribe.ui.common.appCard
import com.minashin1120.voxcribe.ui.theme.Bs
import com.minashin1120.voxcribe.ui.theme.T

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(onNavigate: (String) -> Unit) {
    val ctrl = VoxcribeApp.instance.workspace
    LaunchedEffect(Unit) { ctrl.onScreenStart() }

    AppBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppNavbar("workspace", onNavigate)
            WorkspaceState()
            InputCard(ctrl)
            ThinkingPanel(ctrl)
            ResultCard(ctrl)
            ImproveCard(ctrl)
            HistoryPanel(ctrl)
            Spacer(Modifier.height(24.dp))
        }
    }
    WorkspaceDialogs(ctrl)
}

@Composable
private fun WorkspaceState() {
    val t = T.c
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(t.cardBg).border(1.dp, t.cardBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot()
        Spacer(Modifier.width(6.dp))
        Text("システム起動済み", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ============================ 音声入力カード ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputCard(ctrl: WorkspaceController) {
    val t = T.c
    AppCard {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // ヘッダー（タイトル + 単語リスト / データ / 一括削除）
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(Icons.Filled.Mic, "INPUT", "音声入力")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val active = ctrl.activeSetNames
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BsButton(
                            if (active.isEmpty()) "単語リスト" else active.joinToString(", "),
                            { ctrl.wordSetModalOpen = true; ctrl.loadWordSetStatus() },
                            variant = if (active.isEmpty()) BtnVariant.OUTLINE_PRIMARY else BtnVariant.PRIMARY,
                            size = BtnSize.SM,
                            icon = if (active.isEmpty()) Icons.AutoMirrored.Outlined.MenuBook else Icons.AutoMirrored.Outlined.LibraryBooks,
                        )
                        if (active.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close, null, tint = t.text.copy(alpha = .7f),
                                modifier = Modifier.padding(start = 2.dp).size(18.dp).clickable { ctrl.resetWordSets() }
                            )
                        }
                    }
                    BsButton("データ", { ctrl.openFileManager() }, variant = BtnVariant.OUTLINE_SECONDARY, size = BtnSize.SM, icon = Icons.Outlined.Folder)
                    BsButton("一括削除", { ctrl.requestResetAll() }, variant = BtnVariant.OUTLINE_DANGER, size = BtnSize.SM, icon = Icons.Outlined.DeleteSweep)
                }
            }
            Spacer(Modifier.height(16.dp))

            // 入力方法の切り替え
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.softSurface)
                    .border(1.dp, t.cardBorder, RoundedCornerShape(18.dp)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = t.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("入力方法を選択", color = t.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MethodTab(Modifier.weight(1f), Icons.Filled.Mic, "録音", ctrl.tab == 0) { ctrl.onTabChange(0) }
                    MethodTab(Modifier.weight(1f), Icons.Filled.CloudUpload, "アップロード", ctrl.tab == 1) { ctrl.onTabChange(1) }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (ctrl.tab == 0) RecordPane(ctrl) else UploadPane(ctrl)

            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                SwitchRow("言い直し修正許可", ctrl.rephrase, { ctrl.setRephraseOn(it) })
                SwitchRow("フィラー除去", ctrl.filler, { ctrl.setFillerOn(it) })
            }
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            // モデル / 推論レベル
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    LabelWithIcon("モデル")
                    CustomSelect(Models.ALL.map { it.value to it.label }, ctrl.model, { ctrl.setModel(it) })
                }
                Column {
                    LabelWithIcon("推論レベル")
                    CustomSelect(
                        Models.THINKING.map { it.value to it.label }, ctrl.thinking, { ctrl.setThinkingLevel(it) },
                        enabled = !Models.isStt(ctrl.model)
                    )
                    if (Models.isStt(ctrl.model)) {
                        MutedText(if (Models.isGrok(ctrl.model)) "Grok STTでは推論レベルは使用されません" else "OpenAIモデルでは推論レベルは使用されません", fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ステータス
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(ctrl.status)
                if (ctrl.errorDownloadVisible) {
                    BsButton("音声をダウンロード", { ctrl.downloadLocalAudio() }, variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.SM, icon = Icons.Outlined.Download, pill = true)
                }
            }
            if (ctrl.abortVisible) {
                Spacer(Modifier.height(8.dp))
                BsButton("処理を停止", { ctrl.abortProcessing() }, variant = BtnVariant.OUTLINE_SECONDARY, size = BtnSize.SM, icon = Icons.Outlined.StopCircle, pill = true, enabled = ctrl.abortEnabled)
            }
        }
    }
}

@Composable
private fun LabelWithIcon(text: String) {
    val t = T.c
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Icon(Icons.Outlined.Memory, null, tint = t.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MethodTab(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val t = T.c
    val shape = RoundedCornerShape(12.dp)
    val bgMod = if (active) Modifier.background(Brush.linearGradient(listOf(t.primary, t.primaryHover))) else Modifier.background(t.cardBg)
    val fg = if (active) (if (t.key == "gaming" || t.key == "electronic") Color.Black else Color.White) else t.text
    Row(
        modifier.heightIn(min = 52.dp).clip(shape).then(bgMod).border(1.dp, if (active) t.primary else t.cardBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(if (active) Color.White.copy(alpha = .16f) else t.softPrimary),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = if (active) fg else t.primary, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        if (active) {
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Filled.CheckCircle, null, tint = fg, modifier = Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPill(s: StatusView) {
    val t = T.c
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(t.softSurface).padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot()
        Spacer(Modifier.width(6.dp))
        if (s.badges.isEmpty()) {
            Text(
                s.text, color = if (s.recording) Bs.danger else t.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                RecordingIndicator(s.text)
                s.badges.forEach { Badge(it) }
            }
        }
    }
}

@Composable
private fun RecordingIndicator(text: String) {
    val tr = rememberInfiniteTransition(label = "rec")
    val a by tr.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "ra")
    Text(text, color = Bs.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(a))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordPane(ctrl: WorkspaceController) {
    val t = T.c
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (ctrl.isRecording) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Brush.verticalGradient(listOf(t.softPrimary, Color.Transparent)))) {
                Visualizer(ctrl)
            }
            ctrl.levelText?.let { lv ->
                Spacer(Modifier.height(6.dp))
                Text(
                    lv.text, fontSize = 12.sp, textAlign = TextAlign.Center,
                    color = when (lv.color) { 1 -> Bs.danger; 2 -> Color(0xFFCC9A06); 3 -> Bs.success; else -> t.muted }
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // MP3 / WAV（btn-group）
            Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Bs.secondary, RoundedCornerShape(8.dp))) {
                listOf("mp3" to "MP3", "wav" to "WAV").forEach { (v, l) ->
                    val sel = ctrl.format == v
                    Text(
                        l, color = if (sel) Color.White else Bs.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(if (sel) Bs.secondary else Color.Transparent)
                            .clickable(enabled = !ctrl.isRecording) { ctrl.setFormatValue(v) }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
            Box(Modifier.width(1.dp).height(24.dp).background(t.cardBorder))
            SwitchRow("ノイズ除去", ctrl.noise, { ctrl.setNoiseOn(it) }, enabled = ctrl.noiseSwitchEnabled && !ctrl.isRecording)
        }
        Spacer(Modifier.height(14.dp))
        val idle = !ctrl.isRecording
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            BsButton("新規録音", { ctrl.rec(false) }, variant = BtnVariant.DANGER, size = BtnSize.LG, icon = Icons.Filled.Mic, pill = true, enabled = idle && ctrl.recordButtonsEnabled)
            BsButton("追加", { ctrl.rec(true) }, variant = BtnVariant.OUTLINE_DANGER, size = BtnSize.LG, icon = Icons.Outlined.AddCircleOutline, pill = true, enabled = idle && ctrl.recordButtonsEnabled)
            BsButton(null, { ctrl.togglePause() }, variant = BtnVariant.WARNING, size = BtnSize.LG, icon = if (ctrl.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, pill = true, enabled = !idle)
            BsButton("停止", { ctrl.stopRecording() }, variant = BtnVariant.SECONDARY, size = BtnSize.LG, icon = Icons.Filled.Stop, pill = true, enabled = !idle)
            BsButton(null, { ctrl.cancelRecording() }, variant = BtnVariant.OUTLINE_DARK, size = BtnSize.LG, icon = Icons.Outlined.HighlightOff, pill = true, enabled = !idle)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UploadPane(ctrl: WorkspaceController) {
    val t = T.c
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) ctrl.onFilePicked(uri) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val dashColor = if (t.isGaming) Color(0xFF333333) else Color(0xFFDEE2E6)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.softSurface)
                .drawBehind {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                    )
                    drawRoundRect(dashColor, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
                }
                .clickable { picker.launch(arrayOf("audio/*", "video/mp4", "video/webm")) }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.CloudUpload, null, tint = t.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text("音声ファイルをドロップ", color = t.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("または、クリックしてデバイスから選択", color = t.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            BsButton("ファイルを選択", { picker.launch(arrayOf("audio/*", "video/mp4", "video/webm")) }, size = BtnSize.SM)
        }
        ctrl.selectedFile?.let { f ->
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (t.isDark) Color(0xFF1A1A1A) else Bs.light)
                    .border(1.dp, t.cardBorder, RoundedCornerShape(8.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.AudioFile, null, tint = t.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.2f MB".format(f.size / 1024.0 / 1024.0), color = t.muted, fontSize = 11.sp)
                }
                Icon(Icons.Filled.Close, null, tint = Bs.danger, modifier = Modifier.size(20.dp).clickable { ctrl.removeSelectedFile() })
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BsButton("新規", { ctrl.uploadFile(false) }, size = BtnSize.LG, icon = Icons.AutoMirrored.Outlined.Send, pill = true, enabled = ctrl.uploadButtonsEnabled && ctrl.selectedFile != null)
            BsButton("追加", { ctrl.uploadFile(true) }, variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.LG, icon = Icons.Outlined.AddCircleOutline, pill = true, enabled = ctrl.uploadButtonsEnabled && ctrl.selectedFile != null)
        }
        val up = ctrl.upload
        if (up.visible) {
            Spacer(Modifier.height(12.dp))
            UploadProgressPanel(ctrl, up)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UploadProgressPanel(ctrl: WorkspaceController, up: UploadPanel) {
    val t = T.c
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(if (up.isError) listOf(Color(0xFFFFF5F5), t.softSurface) else listOf(t.cardBg, t.softSurface)))
            .border(1.dp, if (up.isError) Color(0x59DC3545) else t.cardBorder, shape)
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(up.label, color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(up.errorPercentText ?: "${up.percent}%", color = if (up.isError) Bs.danger else t.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        val tr = rememberInfiniteTransition(label = "up")
        val shift by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1350, easing = LinearEasing)), label = "shift")
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0x1A5B5CE2))) {
            Box(
                Modifier.fillMaxWidth(up.percent / 100f).height(12.dp).clip(RoundedCornerShape(999.dp)).drawBehind {
                    if (up.isError) {
                        drawRect(Brush.horizontalGradient(listOf(Color(0xFFDC3545), Color(0xFFE4606D))))
                    } else {
                        val w = size.width
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF5B5CE2), Color(0xFF7C7EF6), Color(0xFF52C7B5), Color(0xFF5B5CE2)),
                                startX = -w * shift, endX = w * 2.2f - w * shift
                            )
                        )
                    }
                }
            )
        }
        if (up.cancelVisible) {
            Spacer(Modifier.height(8.dp))
            BsButton("アップロードをキャンセル", { ctrl.cancelUpload() }, variant = BtnVariant.OUTLINE_DANGER, size = BtnSize.SM, icon = Icons.Outlined.HighlightOff)
        }
        if (up.errorActions) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BsButton("音声をダウンロード", { ctrl.downloadLocalAudio() }, variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.SM, icon = Icons.Outlined.Download)
                Spacer(Modifier.width(6.dp))
                MutedText("通信エラー時はリロード前に保存してください", fontSize = 11.sp)
            }
        }
    }
}

// ============================ 推論プロセス ============================

@Composable
private fun ThinkingPanel(ctrl: WorkspaceController) {
    val t = T.c
    Column(Modifier.fillMaxWidth().appCard(t, radius = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().background(if (ctrl.thinkingOpen) t.softPrimary else t.cardBg)
                .clickable { ctrl.thinkingOpen = !ctrl.thinkingOpen }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Memory, null, tint = if (ctrl.thinkingOpen) t.primary else t.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("推論プロセス", color = if (ctrl.thinkingOpen) t.primary else t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowDown, null,
                tint = t.muted, modifier = Modifier.size(18.dp).rotate(if (ctrl.thinkingOpen) 180f else 0f)
            )
        }
        AnimatedVisibility(ctrl.thinkingOpen, enter = expandVertically(), exit = shrinkVertically()) {
            val scroll = rememberScrollState()
            LaunchedEffect(ctrl.thoughtText.length) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).background(t.softSurface).verticalScroll(scroll).padding(10.dp)) {
                Text(ctrl.thoughtText, color = t.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ============================ 結果カード ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(ctrl: WorkspaceController) {
    val t = T.c
    AppCard {
        Column(Modifier.fillMaxWidth().background(t.cardBg)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(Icons.Outlined.Description, "OUTPUT", "文字起こし結果", small = true)
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    BsButton(null, { ctrl.copyResult() }, variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.SM, icon = Icons.Filled.ContentCopy, enabled = ctrl.copyEnabled)
                    BsButton("再分析", { ctrl.reanalyze() }, variant = BtnVariant.OUTLINE_WARNING, size = BtnSize.SM, icon = Icons.Outlined.Autorenew, enabled = ctrl.reanalyzeEnabled && !ctrl.taskRunning)
                    BsButton(null, { ctrl.deleteModalOpen = true }, variant = BtnVariant.OUTLINE_DANGER, size = BtnSize.SM, icon = Icons.Filled.Delete, enabled = ctrl.deleteAudioEnabled)
                }
            }
            Divider()
        }
        if (ctrl.postprocessVisible) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(t.softPrimary, t.softSurface))).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoFixHigh, null, tint = t.muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    MutedText("結果の後処理（テキストのみ・音声と履歴は送信しません）", fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BsButton("言い直し修正", { ctrl.correctRephrase() }, variant = BtnVariant.SUCCESS, size = BtnSize.SM, icon = Icons.Outlined.FormatQuote, enabled = ctrl.correctRephraseEnabled)
                    BsButton("間隔修正", { ctrl.fixSpacing() }, variant = BtnVariant.OUTLINE_SUCCESS, size = BtnSize.SM, icon = Icons.Outlined.EditNote, enabled = ctrl.fixSpacingEnabled)
                }
            }
            Divider()
        }
        Box(Modifier.fillMaxWidth()) {
            FormInput(
                ctrl.resultText, { ctrl.onResultEdited(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                placeholder = "文字起こし結果がここに表示されます",
                singleLine = false, minLines = 10, borderless = true,
                fontSize = 15.sp, lineHeight = 28.sp,
                padding = androidx.compose.foundation.layout.PaddingValues(22.dp)
            )
            if (ctrl.processingBar) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
                    color = t.primary, trackColor = Color.Transparent
                )
            }
        }
    }
}

// ============================ 改善カード ============================

@Composable
private fun ImproveCard(ctrl: WorkspaceController) {
    val t = T.c
    Column(
        Modifier.fillMaxWidth().appCard(t, border = Color(0x2E5B5CE2).takeIf { t.key == "" } ?: t.cardBorder)
            .background(Brush.linearGradient(listOf(t.cardBg, t.softPrimary)))
    ) {
        CardHeader(bg = Color.Transparent) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = t.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("AIで文章を改善", color = t.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Column(Modifier.padding(16.dp)) {
            CheckRow("音声も参照", ctrl.useAudioForImprove, { ctrl.useAudioForImprove = it })
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormInput(
                    ctrl.instruction, { ctrl.instruction = it },
                    modifier = Modifier.weight(1f), placeholder = "例: 要約して",
                    onDone = { if (ctrl.improveEnabled && !ctrl.taskRunning) ctrl.improve() }
                )
                Spacer(Modifier.width(6.dp))
                BsButton("実行", { ctrl.improve() }, variant = BtnVariant.INFO, enabled = ctrl.improveEnabled && !ctrl.taskRunning)
            }
        }
    }
}

// ============================ 履歴 ============================

@Composable
private fun HistoryPanel(ctrl: WorkspaceController) {
    val t = T.c
    AppCard {
        CardHeader { SectionTitle(Icons.Outlined.History, "RECENT", "履歴", small = true) }
        Column(Modifier.fillMaxWidth().background(t.bg).padding(8.dp)) {
            AlertBox(AlertKind.WARNING, Icons.Outlined.WarningAmber, "履歴と保存データは文字起こし結果に影響します。", fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (ctrl.history.isEmpty()) {
                Text("履歴なし", color = t.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ctrl.history.forEach { h -> HistoryItem(ctrl, h) }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(ctrl: WorkspaceController, h: com.minashin1120.voxcribe.data.HistoryRow) {
    val t = T.c
    var expanded by remember(h.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(t.cardBg).border(1.dp, t.cardBorder, shape)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(t.primary))
        Column(Modifier.weight(1f).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(h.actionType.uppercase(), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(ctrl.historyTime(h), color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.ContentCopy, "コピー", tint = t.primary, modifier = Modifier.size(15.dp).clickable {
                    ctrl.copyToClipboard(h.resultText)
                    VoxcribeApp.instance.toaster.show("コピーしました")
                })
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Delete, "削除", tint = Bs.danger, modifier = Modifier.size(15.dp).clickable { ctrl.requestDeleteHistory(h.id) })
            }
            Spacer(Modifier.height(7.dp))
            if (h.inputSummary.isNotEmpty()) {
                Text(h.inputSummary, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (h.thoughtText.isNotEmpty()) {
                val short = if (h.thoughtText.length > 50) h.thoughtText.take(50) + "..." else h.thoughtText
                Row(Modifier.padding(vertical = 5.dp).clickable { expanded = !expanded }) {
                    Box(Modifier.width(2.dp).height(if (expanded) 40.dp else 20.dp).background(Color(0xFFCCCCCC)))
                    Spacer(Modifier.width(8.dp))
                    Text(short, color = Color(0xFF666666), fontSize = 12.sp, maxLines = if (expanded) Int.MAX_VALUE else 3)
                }
            }
            Text(
                if (h.resultText.isEmpty()) "(結果なし)" else if (h.resultText.length > 100) h.resultText.take(100) + "..." else h.resultText,
                color = t.text, fontSize = 13.sp
            )
        }
    }
}
