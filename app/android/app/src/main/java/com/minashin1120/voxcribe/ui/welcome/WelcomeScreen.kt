@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.minashin1120.voxcribe.ui.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.ui.common.BrandMark
import com.minashin1120.voxcribe.ui.theme.Fonts

/** welcome.html の移植（初回起動時のみ表示） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val tr = rememberInfiniteTransition(label = "welcome")
    val floatY by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3500), RepeatMode.Reverse), label = "float")
    val pulse by tr.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse")
    val wave by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0B1020), Color(0xFF0D1427), Color(0xFF0A1120))))
            .drawBehind {
                // 54px グリッド（下へフェード）
                val step = 54.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = .05f * .15f * 4), Offset(x, 0f), Offset(x, size.height * .7f))
                    x += step
                }
                var y = 0f
                while (y < size.height * .7f) {
                    drawLine(Color.White.copy(alpha = .05f * .15f * 4 * (1 - y / (size.height * .7f))), Offset(0f, y), Offset(size.width, y))
                    y += step
                }
                drawCircle(Brush.radialGradient(listOf(Color(0x386668FF), Color.Transparent), center = Offset(size.width * .1f, size.height * .15f), radius = 336.dp.toPx()), radius = 336.dp.toPx(), center = Offset(size.width * .1f, size.height * .15f))
                drawCircle(Brush.radialGradient(listOf(Color(0x382CB9A8), Color.Transparent), center = Offset(size.width * .95f, size.height * .75f), radius = 336.dp.toPx()), radius = 336.dp.toPx(), center = Offset(size.width * .95f, size.height * .75f))
            }
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // ナビ
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(38)
                Spacer(Modifier.width(10.dp))
                Text("Voxcribe", color = Color(0xFFF5F7FF), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Fonts.inter, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(Color(0xFFF8F9FA)).clickable(onClick = onStart).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("無料で始める", color = Color(0xFF0B1020), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.NorthEast, null, tint = Color(0xFF0B1020), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(40.dp))

            // ヒーローバッジ
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Color(0x409B9EFF), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(Color(0xFF79E2CB)))
                Spacer(Modifier.width(8.dp))
                Text("AI-powered transcription", color = Color(0xFFB9BBFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            val grad = Brush.linearGradient(listOf(Color(0xFF9FA1FF), Color(0xFF75DDCB)))
            Text(
                buildAnnotatedString {
                    append("声を、\n")
                    withStyle(SpanStyle(brush = grad)) { append("伝わる文章") }
                    append("へ。")
                },
                color = Color(0xFFF5F7FF), fontSize = 46.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2.5).sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "高精度なAI文字起こしと文章改善を、ひとつのシンプルなワークスペースで。録音した瞬間から、使えるテキストが生まれます。",
                color = Color(0xFFAEB6CA), fontSize = 16.sp, lineHeight = 26.sp
            )
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.shadow(12.dp, RoundedCornerShape(13.dp), spotColor = Color(0x665B5CE2)).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF7476F3), Color(0xFF5354D3))))
                        .clickable(onClick = onStart).padding(horizontal = 23.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("無料で始める", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Row(
                    Modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(13.dp))
                        .clickable(onClick = onStart).padding(horizontal = 23.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.PlayCircle, null, tint = Color(0xFFF5F7FF), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ログイン", color = Color(0xFFF5F7FF), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("暗号化保存", "自動データ削除", "複数AIモデル").forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF66C9B6), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(it, color = Color(0xFFAEB6CA), fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(36.dp))

            // プロダクトプレビューカード
            Box {
                Column(
                    Modifier.offset(y = (-8 * floatY).dp).fillMaxWidth().clip(RoundedCornerShape(25.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xEB1D243E), Color(0xD910172A))))
                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(25.dp)).padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(Color(0xFF6E70E8), Color(0x33FFFFFF), Color(0x33FFFFFF)).forEach {
                            Box(Modifier.padding(end = 5.dp).size(8.dp).clip(CircleShape).background(it))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("New transcription", color = Color(0xFFC3C9DA), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.MoreHoriz, null, tint = Color(0xFFC3C9DA), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val heights = listOf(.3f, .55f, .8f, .45f, .95f, .6f, .35f, .75f, 1f, .5f, .7f, .4f, .85f, .55f, .3f, .65f, .45f)
                        heights.forEachIndexed { i, h ->
                            val phase = ((wave + i / 17f) % 1f)
                            val scale = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(phase * Math.PI.toFloat()))
                            Box(
                                Modifier.weight(1f).height((56 * h * scale).dp).clip(RoundedCornerShape(3.dp))
                                    .background(Brush.verticalGradient(listOf(Color(0xFF686AEB), Color(0xFF74D7C5))))
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(Color(0xFFEF6373)))
                        Spacer(Modifier.width(6.dp))
                        Text("00:24", color = Color(0xFFF5F7FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("Recording", color = Color(0xFF8C93A8), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("TRANSCRIPT", color = Color(0xFF8C93A8), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("本日のミーティングでは、次のプロジェクトの方針について確認します。", color = Color(0xFFE4E8F4), fontSize = 14.sp, lineHeight = 22.sp)
                    Text("まずは現在の進捗から共有をお願いします。", color = Color(0x80E4E8F4), fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // フローティングノート
            Row(
                Modifier.offset(y = (6 * floatY).dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF2FFFFFF)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFF5B5CE2), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text("AI enhancement", color = Color(0xFF182235), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("文章を自然に整えました", color = Color(0xFF758095), fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF27B783), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(32.dp))

            // 特徴
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp))
            ) {
                Feature("01", Icons.Filled.GraphicEq, "高精度な文字起こし", "文脈を読み取り、専門用語にも対応。")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                Feature("02", Icons.Outlined.AutoAwesome, "AI文章改善", "要約や書き換えもワンクリック。")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                Feature("03", Icons.Outlined.VerifiedUser, "安心のデータ管理", "暗号化と自動削除で安全に。")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Feature(num: String, icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(num, color = Color(0xFF6E7590), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x266668FF)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color(0xFFB9BBFF), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color(0xFFF5F7FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(body, color = Color(0xFFAEB6CA), fontSize = 13.sp)
        }
    }
}
