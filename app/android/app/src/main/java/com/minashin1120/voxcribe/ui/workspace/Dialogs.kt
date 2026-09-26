package com.minashin1120.voxcribe.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.minashin1120.voxcribe.data.SavedAudio
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.ui.common.AlertBox
import com.minashin1120.voxcribe.ui.common.AlertKind
import com.minashin1120.voxcribe.ui.common.AppModal
import com.minashin1120.voxcribe.ui.common.BsButton
import com.minashin1120.voxcribe.ui.common.BtnSize
import com.minashin1120.voxcribe.ui.common.BtnVariant
import com.minashin1120.voxcribe.ui.common.CheckRow
import com.minashin1120.voxcribe.ui.common.CustomSelect
import com.minashin1120.voxcribe.ui.common.Divider
import com.minashin1120.voxcribe.ui.common.FormInput
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.theme.Bs
import com.minashin1120.voxcribe.ui.theme.T
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WorkspaceDialogs(ctrl: WorkspaceController) {
    if (ctrl.deleteModalOpen) DeleteAudioModal(ctrl)
    ctrl.micErrorMessage?.let { MicErrorModal(it) { ctrl.micErrorMessage = null } }
    if (ctrl.fileManagerOpen) FileManagerModal(ctrl)
    if (ctrl.wordSetModalOpen) WordSetModal(ctrl)
    if (ctrl.wordSetManageOpen) WordSetManageModal(ctrl)
    ctrl.apiKeyPrompt?.let { ApiKeyOverlay(ctrl, it) }
}

// ---------------- 音声破棄 ----------------

@Composable
private fun DeleteAudioModal(ctrl: WorkspaceController) {
    AppModal("音声破棄", onDismiss = { ctrl.deleteModalOpen = false }, footer = {
        BsButton("キャンセル", { ctrl.deleteModalOpen = false }, variant = BtnVariant.SECONDARY)
        BsButton("削除", { ctrl.deleteAudio() }, variant = BtnVariant.DANGER)
    }) {
        Text("音声を削除しますか？", color = T.c.text)
        Text("再分析不可になります。", color = Bs.danger, fontSize = 12.sp)
    }
}

// ---------------- 録音を開始できません ----------------

@Composable
private fun MicErrorModal(message: String, onClose: () -> Unit) {
    var helpOpen by remember(message) { mutableStateOf(false) }
    AppModal(
        "録音を開始できません", onDismiss = onClose, titleColor = Bs.danger, titleIcon = Icons.Outlined.WarningAmber,
        footer = { BsButton("閉じる", onClose, variant = BtnVariant.SECONDARY) }
    ) {
        Text(message, color = T.c.text)
        Spacer(Modifier.height(12.dp))
        BsButton(
            if (helpOpen) "対処方法を閉じる" else "対処方法を確認", { helpOpen = !helpOpen },
            variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.SM,
            icon = if (helpOpen) Icons.Outlined.ExpandLess else Icons.AutoMirrored.Outlined.HelpOutline
        )
        AnimatedVisibility(helpOpen) {
            Column(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bs.infoBg).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("1. アプリを一度閉じて再度開き、もう一度録音をお試しください。", color = Bs.infoText, fontSize = 13.sp)
                Text("2. 改善しない場合は、ノイズ除去スイッチを反対側にして一度録音を開始・停止し、希望の状態に戻して再試行してください。", color = Bs.infoText, fontSize = 13.sp)
                Text("3. それでも改善しない場合は、イヤフォン等を外し、端末を再起動してください。", color = Bs.infoText, fontSize = 13.sp)
            }
        }
    }
}

// ---------------- 保存データ ----------------

@Composable
private fun FileManagerModal(ctrl: WorkspaceController) {
    val t = T.c
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    AppModal("保存データ (${VoxRetention.label()}保持)", onDismiss = { ctrl.fileManagerOpen = false }, footer = {
        BsButton("更新", { ctrl.loadFileMetadata() })
    }) {
        AlertBox(AlertKind.INFO, Icons.Outlined.Info, "保存データは文字起こし結果に影響します。", fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        when {
            ctrl.filesLoading -> MutedText("Loading...")
            ctrl.files.isEmpty() -> Text("保存された音声はありません", color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            else -> Column {
                ctrl.files.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    FileRow(ctrl, f, player, playing == f.file.name) { name -> playing = name }
                }
            }
        }
    }
}

@Composable
private fun FileRow(ctrl: WorkspaceController, f: SavedAudio, player: ExoPlayer, isActive: Boolean, onActivate: (String?) -> Unit) {
    val t = T.c
    val mb = f.size / 1024.0 / 1024.0
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.displayName, color = t.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Delete, "削除", tint = Bs.danger, modifier = Modifier.size(20.dp).clickable { ctrl.requestDeleteFile(f) })
        }
        Spacer(Modifier.height(4.dp))
        if (f.size <= 100L * 1024 * 1024) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniPlayer(f, player, isActive, onActivate, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                MutedText("%.2f MB".format(mb))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText("%.2f MB".format(mb))
                Spacer(Modifier.width(8.dp))
                BsButton("並列DL", { ctrl.exportFile(f) }, variant = BtnVariant.OUTLINE_PRIMARY, size = BtnSize.SM, icon = Icons.Outlined.Download)
            }
        }
    }
}

/** <audio controls> 相当の簡易プレイヤー */
@Composable
private fun MiniPlayer(f: SavedAudio, player: ExoPlayer, isActive: Boolean, onActivate: (String?) -> Unit, modifier: Modifier) {
    val t = T.c
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isActive) {
        if (!isActive) {
            isPlaying = false
            pos = 0f
            return@LaunchedEffect
        }
        while (true) {
            isPlaying = player.isPlaying
            val d = player.duration
            pos = if (d > 0) player.currentPosition.toFloat() / d else 0f
            if (player.playbackState == Player.STATE_ENDED) isPlaying = false
            delay(200)
        }
    }
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(t.softSurface).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isActive && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = t.primary,
            modifier = Modifier.size(28.dp).clickable {
                if (!isActive) {
                    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f.file)))
                    player.prepare()
                    player.play()
                    onActivate(f.file.name)
                } else if (player.isPlaying) player.pause()
                else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }
        )
        Slider(
            value = pos, onValueChange = { v -> if (isActive && player.duration > 0) { pos = v; player.seekTo((v * player.duration).toLong()) } },
            modifier = Modifier.weight(1f).height(25.dp),
            colors = SliderDefaults.colors(thumbColor = t.primary, activeTrackColor = t.primary)
        )
    }
}

private object VoxRetention {
    fun label(): String = "${com.minashin1120.voxcribe.VoxcribeApp.instance.prefs.retentionMinutes}分"
}

// ---------------- 単語リストの有効化 ----------------

@Composable
private fun WordSetModal(ctrl: WorkspaceController) {
    val t = T.c
    AppModal("単語リストの有効化", onDismiss = { ctrl.wordSetModalOpen = false }, footer = {
        Text(
            "リストの編集・作成", color = t.primary, fontSize = 13.sp,
            modifier = Modifier.weight(1f).clickable { ctrl.openWordSetManage() }
        )
        BsButton("閉じる", { ctrl.wordSetModalOpen = false }, variant = BtnVariant.SECONDARY)
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MutedText("使用する単語セットにチェックを入れてください。", Modifier.weight(1f))
            BsButton("選択リセット", { ctrl.resetWordSets() }, variant = BtnVariant.OUTLINE_SECONDARY, size = BtnSize.SM)
        }
        Spacer(Modifier.height(12.dp))
        if (ctrl.wordSets.isEmpty()) {
            Row {
                Text("セットがありません。", color = t.muted, fontSize = 13.sp)
                Text("新しく作成", color = t.primary, fontSize = 13.sp, modifier = Modifier.clickable { ctrl.openWordSetManage() })
            }
        } else {
            ctrl.wordSets.forEachIndexed { i, s ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, color = t.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Edit, "編集", tint = t.muted, modifier = Modifier.size(18.dp).clickable { ctrl.openWordSetManage(s.id) })
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = s.isActive, onCheckedChange = { ctrl.toggleWordSet(s.id) },
                        colors = SwitchDefaults.colors(checkedTrackColor = t.primary)
                    )
                }
            }
        }
    }
}

// ---------------- 単語リスト管理 ----------------

@Composable
private fun WordSetManageModal(ctrl: WorkspaceController) {
    val t = T.c
    var newSetName by remember { mutableStateOf("") }
    AppModal("単語リスト管理", onDismiss = { ctrl.wordSetManageOpen = false }, footer = {
        BsButton("戻る", { ctrl.backToWordSetModal() }, variant = BtnVariant.OUTLINE_SECONDARY)
        BsButton("閉じる", { ctrl.wordSetManageOpen = false }, variant = BtnVariant.SECONDARY)
    }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (t.isDark) Color(0xFF1A1A1A) else Bs.light).padding(10.dp)
        ) {
            Text("新しいセットを作成", color = t.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormInput(newSetName, { newSetName = it }, Modifier.weight(1f), placeholder = "セット名 (例: 専門用語)", small = true)
                Spacer(Modifier.width(6.dp))
                BsButton("作成", {
                    if (newSetName.isNotBlank()) {
                        ctrl.createWordSet(newSetName)
                        newSetName = ""
                    }
                }, size = BtnSize.SM)
            }
        }
        Spacer(Modifier.height(16.dp))
        val options = listOf<Pair<Long?, String>>(null to "-- 管理するセットを選択してください --") + ctrl.wordSets.map { it.id to it.name }
        CustomSelect(options, ctrl.manageSelectedId, { ctrl.refreshManageList(it) })
        Spacer(Modifier.height(16.dp))
        val set = ctrl.wordSets.firstOrNull { it.id == ctrl.manageSelectedId }
        if (set != null) WordSetCard(ctrl, set)
    }
}

@Composable
private fun WordSetCard(ctrl: WorkspaceController, set: com.minashin1120.voxcribe.data.WordSetRow) {
    val t = T.c
    val scope = rememberCoroutineScope()
    var reading by remember(set.id) { mutableStateOf("") }
    var replacement by remember(set.id) { mutableStateOf("") }
    var autoYomi by remember(set.id) { mutableStateOf(false) }
    var yomiJob by remember { mutableStateOf<Job?>(null) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, t.cardBorder, shape)) {
        Row(
            Modifier.fillMaxWidth().background(if (set.isActive) Color(0xFF0D6EFD) else if (t.isDark) Color(0xFF1A1A1A) else Bs.light).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val fg = if (set.isActive) Color.White else t.text
            Text(set.name, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (set.isActive) {
                Spacer(Modifier.width(6.dp))
                Text("有効中", color = Color(0xFF0D6EFD), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Delete, "セットを削除", tint = if (set.isActive) Color.White else Bs.danger, modifier = Modifier.size(18.dp).clickable { ctrl.requestDeleteWordSet(set.id) })
        }
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("読み (検知)", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("変換後 (単語)", color = t.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(36.dp))
            }
            Divider()
            if (ctrl.manageWords.isEmpty()) {
                MutedText("単語が登録されていません。", Modifier.padding(vertical = 8.dp))
            } else {
                ctrl.manageWords.forEach { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.reading, color = t.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(w.replacement, color = t.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("削除", color = Bs.danger, fontSize = 12.sp, modifier = Modifier.width(36.dp).clickable { ctrl.deleteWord(w.id) })
                    }
                    Divider()
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormInput(reading, { reading = it }, Modifier.weight(1f), placeholder = "読み", small = true)
                Spacer(Modifier.width(6.dp))
                FormInput(replacement, { v ->
                    replacement = v
                    if (autoYomi) {
                        yomiJob?.cancel()
                        val word = v.trim()
                        if (word.isNotEmpty()) {
                            yomiJob = scope.launch {
                                delay(600)
                                ctrl.generateYomigana(word)?.let { reading = it }
                            }
                        }
                    }
                }, Modifier.weight(1f), placeholder = "変換後", small = true)
                Spacer(Modifier.width(6.dp))
                BsButton("追加", {
                    if (ctrl.addWord(set.id, reading, replacement)) {
                        reading = ""
                        replacement = ""
                    }
                }, size = BtnSize.SM)
            }
            CheckRow("変換後から読みを自動生成", autoYomi, { autoYomi = it })
        }
    }
}

// ---------------- APIキー（Liquid Glass オーバーレイ） ----------------

@Composable
private fun ApiKeyOverlay(ctrl: WorkspaceController, p: ApiKeyPrompt) {
    val t = T.c
    val dark = t.key == "gaming" || t.key == "electronic"
    var input by remember(p.keyType, p.model) { mutableStateOf("") }
    Dialog(onDismissRequest = { ctrl.akClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 440.dp)
                .shadow(30.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(if (dark) Color(0xC70C1014) else Color(0xF2FFFFFF))
                .border(1.dp, Color.White.copy(alpha = if (dark) .12f else .6f), RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            val fg = if (dark) Color.White else Color(0xFF1C1C1E)
            when (p.view) {
                AkView.INPUT -> {
                    val (title, msg, ph) = when (p.keyType) {
                        KeyType.XAI -> Triple("xAI (Grok) APIキーの設定", "Grok STT モデルを使用するには xAI API キーが必要です。", "xai-...")
                        KeyType.OPENAI -> Triple("OpenAI APIキーの設定", "GPT-Transcribe / GPT-Live / Whisperモデルを使用するには OpenAI API キーが必要です。", "sk-...")
                        KeyType.GEMINI -> Triple("Gemini APIキーの設定", "Gemini モデルを使用するには Gemini API キーが必要です。", "AIzaSy...")
                    }
                    AkHeader(title, fg) { ctrl.akClose() }
                    Text(msg, color = fg.copy(alpha = .75f), fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    FormInput(input, { input = it }, Modifier.fillMaxWidth(), placeholder = ph, password = true, onDone = { ctrl.akSave(input) })
                    p.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Color(0xFFFF3B30), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BsButton("別のモデルを選択", { ctrl.akShowSwitch() }, Modifier.weight(1f), variant = BtnVariant.OUTLINE_SECONDARY, fillWidth = true)
                        AkSaveButton(if (p.saving) "保存中..." else "保存して送信", p.saving, Modifier.weight(1f), t.isGaming) { ctrl.akSave(input) }
                    }
                }
                AkView.SWITCH -> {
                    AkHeader("モデルを選択", fg) { ctrl.akClose() }
                    Text("APIキーが設定されているモデルを選んでください。", color = fg.copy(alpha = .75f), fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    val models = p.switchModels ?: emptyList()
                    when {
                        models.isEmpty() -> Text("利用可能なモデルがありません。", color = fg.copy(alpha = .6f), fontSize = 13.sp)
                        models.none { it.first != p.model } -> MutedText("現在のモデル以外に利用可能なモデルはありません。")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            models.filter { it.first != p.model }.forEach { (v, l) ->
                                BsButton(l, { ctrl.akPickModel(v) }, variant = BtnVariant.OUTLINE_PRIMARY, fillWidth = true)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    BsButton("戻る", { ctrl.akBackToInput() }, variant = BtnVariant.OUTLINE_SECONDARY, fillWidth = true)
                }
                AkView.DISCARD -> {
                    AkHeader("音声ファイルを破棄", fg, null)
                    Text("録音した音声ファイルは破棄されます。", color = fg.copy(alpha = .75f), fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    BsButton("音声をダウンロード", { ctrl.akDownload() }, variant = BtnVariant.OUTLINE_PRIMARY, icon = Icons.Outlined.Download, fillWidth = true)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BsButton("戻る", { ctrl.akBackToInput() }, Modifier.weight(1f), variant = BtnVariant.OUTLINE_SECONDARY, fillWidth = true)
                        BsButton("破棄する", { ctrl.akConfirmDiscard() }, Modifier.weight(1f), variant = BtnVariant.DANGER, fillWidth = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun AkHeader(title: String, fg: Color, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1F007AFF)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Key, null, tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(title, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onClose != null) Icon(Icons.Filled.Close, "閉じる", tint = fg.copy(alpha = .6f), modifier = Modifier.size(22.dp).clickable { onClose() })
    }
}

@Composable
private fun AkSaveButton(text: String, saving: Boolean, modifier: Modifier, gaming: Boolean, onClick: () -> Unit) {
    val grad = if (gaming) listOf(Color(0xFF00FFCC), Color(0xFF00997A)) else listOf(Color(0xFF007AFF), Color(0xFF0051D5))
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(grad))
            .clickable(enabled = !saving, onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (gaming) Color.Black else Color.White
        if (saving) {
            CircularProgressIndicator(Modifier.size(14.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
