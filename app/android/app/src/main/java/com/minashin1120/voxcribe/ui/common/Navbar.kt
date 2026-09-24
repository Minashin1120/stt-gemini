package com.minashin1120.voxcribe.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.ui.theme.T

@Composable
fun BrandMark(size: Int = 42) {
    Box(
        Modifier.size(size.dp)
            .shadow(8.dp, RoundedCornerShape(13.dp), spotColor = Color(0x475B5CE2))
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF7374F5), Color(0xFF4B4CCC)))),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size((size * 0.5).dp)) }
}

/** base.html の .app-navbar（モバイル: トグルでドロップダウンパネル） */
@Composable
fun AppNavbar(current: String, onNavigate: (String) -> Unit) {
    val t = T.c
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x121E2A44), spotColor = Color(0x121E2A44))
                .clip(RoundedCornerShape(20.dp)).background(t.navbarBg)
                .border(1.dp, if (t.key == "") Color(0xD9E0E6EF) else t.cardBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f).clickable { onNavigate("workspace") }, verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Voxcribe", color = t.navbarText.takeIf { t.key == "business" || t.key == "material2" } ?: t.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                    Text("AI TRANSCRIPTION", color = t.muted.takeIf { t.key != "business" && t.key != "material2" } ?: t.navbarText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
                }
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).clickable { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (open) Icons.Filled.Close else Icons.Filled.Menu, if (open) "ナビゲーションを閉じる" else "ナビゲーションを開く", tint = t.navbarText)
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x241E2A44), spotColor = Color(0x241E2A44))
                    .clip(RoundedCornerShape(16.dp)).background(t.navbarBg)
                    .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                NavLink(Icons.Outlined.Dashboard, "ワークスペース", current == "workspace") { open = false; onNavigate("workspace") }
                NavLink(Icons.Outlined.Tune, "設定", current == "settings") { open = false; onNavigate("settings") }
            }
        }
    }
}

@Composable
private fun NavLink(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val t = T.c
    val fg = if (active) t.primary else t.navbarText
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(if (active) t.softPrimary else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
