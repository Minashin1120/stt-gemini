package com.minashin1120.voxcribe.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.Models
import com.minashin1120.voxcribe.data.SecretStore
import com.minashin1120.voxcribe.data.SecretStore.KeyType
import com.minashin1120.voxcribe.data.WordListTransfer
import com.minashin1120.voxcribe.ui.ThemeState
import com.minashin1120.voxcribe.ui.common.AlertBox
import com.minashin1120.voxcribe.ui.common.AlertKind
import com.minashin1120.voxcribe.ui.common.AppBackground
import com.minashin1120.voxcribe.ui.common.AppNavbar
import com.minashin1120.voxcribe.ui.common.BsButton
import com.minashin1120.voxcribe.ui.common.BtnVariant
import com.minashin1120.voxcribe.ui.common.ConfirmRequest
import com.minashin1120.voxcribe.ui.common.CustomSelect
import com.minashin1120.voxcribe.ui.common.Divider
import com.minashin1120.voxcribe.ui.common.FormInput
import com.minashin1120.voxcribe.ui.common.MutedText
import com.minashin1120.voxcribe.ui.common.appCard
import com.minashin1120.voxcribe.ui.theme.Bs
import com.minashin1120.voxcribe.ui.theme.T
import com.minashin1120.voxcribe.ui.theme.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** settings.html の移植（APIとデータ / 外観 / プライバシー / 危険な操作） */
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit) {
    val app = VoxcribeApp.instance
    val t = T.c
    var version by remember { mutableStateOf(0) }
    val hasGemini = remember(version) { app.secrets.has(KeyType.GEMINI) }
    val hasXai = remember(version) { app.secrets.has(KeyType.XAI) }
    val hasOpenai = remember(version) { app.secrets.has(KeyType.OPENAI) }
    var gemini by remember { mutableStateOf("") }
    var xai by remember { mutableStateOf("") }
    var openai by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf(app.prefs.retentionMinutes.toString()) }
    var yomigana by remember { mutableStateOf(app.prefs.yomiganaModel) }
    var theme by remember { mutableStateOf(ThemeState.key) }
    var transferringWordLists by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportWordLists = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferringWordLists = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { WordListTransfer.export(app.db, it) }
                        ?: error("保存先を開けませんでした。")
                }
                app.toaster.show("単語リストをエクスポートしました。")
            } catch (_: Exception) {
                app.toaster.show("単語リストのエクスポートに失敗しました。", true)
            } finally {
                transferringWordLists = false
            }
        }
    }
    val importWordLists = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferringWordLists = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { WordListTransfer.importFrom(app.db, it) }
                        ?: error("ファイルを開けませんでした。")
                }
                app.workspace.loadWordSetStatus()
                app.toaster.show("単語セット${result.sets}件、単語${result.words}件をインポートしました。")
            } catch (e: IllegalArgumentException) {
                app.toaster.show(e.message ?: "単語リストの形式が正しくありません。", true)
            } catch (_: Exception) {
                app.toaster.show("単語リストのインポートに失敗しました。", true)
            } finally {
                transferringWordLists = false
            }
        }
    }

    AppBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppNavbar("settings", onNavigate)
            Column(Modifier.padding(top = 8.dp)) {
                Text("PREFERENCES", color = t.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
                Text("設定", color = t.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
                Text("モデル接続、データ保持、外観を管理します。", color = t.muted, fontSize = 15.sp)
            }

            // ---------- APIとデータ ----------
            SettingsCard(Icons.Outlined.Key, "APIとデータ") {
                MutedText("APIキーを入力してください。キーは安全に暗号化されて保存されます。", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                KeyStatus("Gemini ステータス: ", hasGemini)
                KeyStatus("xAI (Grok) ステータス: ", hasXai)
                KeyStatus("OpenAI ステータス: ", hasOpenai)
                Spacer(Modifier.height(6.dp))
                FieldLabel("Gemini API キー (3.5 Flash / 3.0 Flash / 3.1 Flash-Lite)")
                FormInput(gemini, { gemini = it.take(512) }, Modifier.fillMaxWidth(), placeholder = if (hasGemini) "変更する場合のみ入力" else "AIzaSy...", password = true)
                Spacer(Modifier.height(14.dp))
                FieldLabel("xAI (Grok STT) API キー")
                FormInput(xai, { xai = it.take(512) }, Modifier.fillMaxWidth(), placeholder = if (hasXai) "変更する場合のみ入力" else "xai-...", password = true)
                FormText("Grok STTモデルを使用する場合に必要です。xAIコンソールから取得してください。")
                Spacer(Modifier.height(14.dp))
                FieldLabel("OpenAI API キー (GPT-Transcribe / GPT-Live)")
                FormInput(openai, { openai = it.take(512) }, Modifier.fillMaxWidth(), placeholder = if (hasOpenai) "変更する場合のみ入力" else "sk-...", password = true)
                FormText("GPT-Transcribe / GPT-Live Transcribeモデルを使用する場合に必要です。OpenAIコンソールから取得してください。")
                Spacer(Modifier.height(14.dp))
                FieldLabel("履歴・データの保持時間 (分)", bold = true)
                FormInput(retention, { retention = it.filter { c -> c.isDigit() }.take(4) }, Modifier.fillMaxWidth(), number = true)
                FormText("録音データや会話履歴を保持する時間（分）を設定します。この時間を過ぎると自動的に削除されます。")
                Spacer(Modifier.height(14.dp))
                FieldLabel("読み自動生成 モデル", bold = true)
                CustomSelect(Models.ALL.map { it.value to it.label }, yomigana, { v ->
                    yomigana = v
                    app.prefs.yomiganaModel = v
                    app.toaster.show("読み自動生成モデルを保存しました")
                })
                FormText("単語追加時に「変換後」の単語から「読み」を自動生成する際に使用するモデルです（Geminiモデル推奨）。")
                Spacer(Modifier.height(16.dp))
                BsButton("設定を保存", {
                    val msgs = mutableListOf<Pair<String, Boolean>>()
                    if (gemini.trim().isNotEmpty()) { app.secrets.put(KeyType.GEMINI, gemini.trim()); msgs += "Gemini APIキーを保存しました。" to false }
                    if (xai.trim().isNotEmpty()) {
                        if (SecretStore.isPlausibleXaiApiKey(xai)) {
                            app.secrets.put(KeyType.XAI, xai.trim())
                            msgs += "xAI APIキーを保存しました。" to false
                        } else {
                            msgs += "xAI APIキーの形式が正しくありません（xai- で始まるキーを入力してください）。" to true
                        }
                    }
                    if (openai.trim().isNotEmpty()) { app.secrets.put(KeyType.OPENAI, openai.trim()); msgs += "OpenAI APIキーを保存しました。" to false }
                    val r = retention.toIntOrNull()
                    when {
                        r == null -> msgs += "保存期間には数値を入力してください。" to true
                        r < 1 || r > 1440 -> msgs += "保存期間は1〜1440分の範囲で指定してください。" to true
                        else -> { app.prefs.retentionMinutes = r; msgs += "保存期間の設定を更新しました。" to false }
                    }
                    gemini = ""; xai = ""; openai = ""
                    version++
                    msgs.forEach { (m, err) -> app.toaster.show(m, err) }
                }, icon = Icons.Outlined.Check)
            }

            // ---------- 単語リスト ----------
            SettingsCard(Icons.Outlined.SwapHoriz, "単語リスト") {
                MutedText("すべての単語セットをJSONファイルに書き出したり、書き出したファイルから追加できます。Web版とAndroid版の間でも移行できます。", fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BsButton(
                        "エクスポート",
                        { exportWordLists.launch("voxcribe-word-lists-${LocalDate.now()}.json") },
                        modifier = Modifier.weight(1f),
                        variant = BtnVariant.OUTLINE_PRIMARY,
                        icon = Icons.Outlined.Download,
                        enabled = !transferringWordLists,
                    )
                    BsButton(
                        "インポート",
                        { importWordLists.launch(arrayOf("application/json", "text/json")) },
                        modifier = Modifier.weight(1f),
                        variant = BtnVariant.OUTLINE_SECONDARY,
                        icon = Icons.Outlined.SwapHoriz,
                        enabled = !transferringWordLists,
                    )
                }
                FormText("インポートした単語セットは、現在のリストを残したまま追加されます。")
            }

            // ---------- 外観 ----------
            SettingsCard(Icons.Outlined.Palette, "外観") {
                FieldLabel("デザインテーマ", bold = true)
                CustomSelect(Themes.OPTIONS, theme, { v ->
                    theme = v
                    ThemeState.key = v // 即時プレビュー
                })
                FormText("選択するとプレビューが即座に適用されます。")
                Spacer(Modifier.height(14.dp))
                BsButton("デザイン設定を保存", {
                    app.prefs.theme = theme
                    app.toaster.show("デザインテーマを保存しました")
                })
            }

            // ---------- プライバシー ----------
            PrivacyCard()

            // ---------- 危険な操作 ----------
            Column(Modifier.fillMaxWidth().appCard(t, border = Color(0x33DC3545))) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x0DDC3545)).padding(horizontal = 20.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = Bs.dangerText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("危険な操作", color = Bs.dangerText, fontWeight = FontWeight.Bold)
                }
                Divider()
                Column(Modifier.padding(22.dp)) {
                    Text(
                        "この操作は取り消せません。履歴、設定、APIキー、保存されている音声データなど端末内のデータがすべて完全に削除されます。",
                        color = Bs.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    BsButton("端末内データをすべて削除する", {
                        app.workspace.confirm = ConfirmRequest("本当に端末内のデータをすべて削除しますか？\nこの操作は取り消せず、すべてのデータが失われます。") {
                            try {
                                app.workspace.wipeAllData()
                                ThemeState.key = ""
                                app.toaster.show("端末内のデータをすべて削除しました。")
                                onNavigate("welcome")
                            } catch (e: Exception) {
                                app.toaster.show("削除中にエラーが発生しました。", true)
                            }
                        }
                    }, variant = BtnVariant.OUTLINE_DANGER, fillWidth = true)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    val t = T.c
    Column(Modifier.fillMaxWidth().appCard(t)) {
        Row(Modifier.fillMaxWidth().background(t.cardBg).padding(horizontal = 20.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(31.dp).clip(RoundedCornerShape(9.dp)).background(t.softPrimary), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = t.primary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = t.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Divider()
        Column(Modifier.padding(22.dp)) { content() }
    }
}

@Composable
private fun KeyStatus(label: String, has: Boolean) {
    AlertBox(
        if (has) AlertKind.SUCCESS else AlertKind.WARNING, null,
        label + if (has) "APIキー設定済み (暗号化されています)" else "未設定",
        Modifier.padding(bottom = 8.dp), fontSize = 14.sp
    )
}

@Composable
private fun FieldLabel(text: String, bold: Boolean = false) {
    Text(text, color = T.c.text, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.padding(bottom = 7.dp))
}

@Composable
private fun FormText(text: String) {
    MutedText(text, Modifier.padding(top = 4.dp), fontSize = 12.sp)
}

@Composable
private fun PrivacyCard() {
    val t = T.c
    val tr = rememberInfiniteTransition(label = "shield")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "sp")
    Column(
        Modifier.fillMaxWidth().appCard(t).background(Brush.linearGradient(listOf(t.cardBg, t.softPrimary))).padding(24.dp)
    ) {
        Box(
            Modifier.size(76.dp).offset(y = (-6 * p).dp).rotate(2 * p).clip(RoundedCornerShape(24.dp)).background(t.softPrimary),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Shield, null, tint = t.primary, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("プライバシーを優先", color = t.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        MutedText("APIキーは端末内で暗号化され、音声と履歴は設定した保持時間を過ぎると自動で削除されます。データはサーバーへ送信されず、端末から各AIのAPIへ直接送られます。", fontSize = 13.sp)
    }
}
