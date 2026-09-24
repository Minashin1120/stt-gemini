@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.minashin1120.voxcribe.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minashin1120.voxcribe.R

/** style.css の CSS 変数（--bg-color 等）に対応するテーマトークン */
data class AppTheme(
    val key: String,
    val bg: Color,
    val text: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val primary: Color,
    val primaryHover: Color,
    val navbarBg: Color,
    val navbarText: Color,
    val inputBg: Color,
    val inputText: Color,
    val inputBorder: Color,
    val font: FontFamily,
    val radius: Dp,
    /** CSS box-shadow 相当: ぼかし有り=elevation、ぼかし無し（retro/stylish）=オフセット影 */
    val shadowElevation: Dp,
    val hardShadowOffset: Dp = 0.dp,
    val hardShadowColor: Color = Color.Transparent,
    val glowColor: Color = Color.Transparent,
    val visualizer: Color,
    val isDark: Boolean = false,
    val softPrimary: Color = Color(0x1A5B5CE2),
    val softSurface: Color = Color(0xD9F6F8FC),
    val muted: Color = Color(0xFF758095),
) {
    val isGaming get() = key == "gaming"
}

object Fonts {
    val inter = FontFamily(
        Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.inter, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    )
    val zenOldMincho = FontFamily(Font(R.font.zen_old_mincho, FontWeight.Normal))
    // Roboto / Noto Sans JP は Android のシステムフォント（sans-serif）そのもの
    val roboto = FontFamily.SansSerif
    val mono = FontFamily.Monospace
}

object Themes {
    val OPTIONS = listOf(
        "" to "モダン (デフォルト)",
        "material3" to "Material 3 (最新Google風)",
        "material2" to "Material 2 (クラシックMaterial)",
        "classic" to "Classic Google (レトロウェブ)",
        "gaming" to "ゲーミング (Gaming)",
        "electronic" to "エレクトロニック (Cyber)",
        "retro" to "昭和レトロ",
        "stylish" to "スタイリッシュ",
        "business" to "ビジネス",
    )

    private val modern = AppTheme(
        key = "",
        bg = Color(0xFFF4F7FB), text = Color(0xFF182235),
        cardBg = Color(0xF0FFFFFF), cardBorder = Color(0xFFE4EAF2),
        primary = Color(0xFF5B5CE2), primaryHover = Color(0xFF494ACA),
        navbarBg = Color(0xF0FFFFFF), navbarText = Color(0xFF5D687A),
        inputBg = Color(0xFFF8FAFC), inputText = Color(0xFF182235), inputBorder = Color(0xFFDCE3EC),
        font = Fonts.inter, radius = 18.dp, shadowElevation = 6.dp,
        visualizer = Color(0xFF696AF0),
    )

    fun of(key: String): AppTheme = when (key) {
        "gaming" -> modern.copy(
            key = key, bg = Color(0xFF050505), text = Color.White,
            cardBg = Color(0xE6141414), cardBorder = Color(0xFFFF00FF),
            primary = Color(0xFF00FFCC), primaryHover = Color(0xFF00CCA3),
            navbarBg = Color(0xCC000000), navbarText = Color.White,
            inputBg = Color.Black, inputText = Color(0xFF00FFCC), inputBorder = Color(0xFFFF00FF),
            font = Fonts.roboto, radius = 0.dp, shadowElevation = 0.dp,
            glowColor = Color(0x66FF00FF), visualizer = Color(0xFF00FFCC), isDark = true,
        )
        "retro" -> modern.copy(
            key = key, bg = Color(0xFFF4E4BC), text = Color(0xFF4E342E),
            cardBg = Color(0xFFFFFCF0), cardBorder = Color(0xFF8D6E63),
            primary = Color(0xFFD84315), primaryHover = Color(0xFFBF360C),
            navbarBg = Color(0xFFD7CCC8), navbarText = Color(0xFF3E2723),
            inputBg = Color(0xFFFFF8E1), inputText = Color(0xFF4E342E), inputBorder = Color(0xFF8D6E63),
            font = Fonts.zenOldMincho, radius = 4.dp, shadowElevation = 0.dp,
            hardShadowOffset = 4.dp, hardShadowColor = Color(0x4D5D4037), visualizer = Color(0xFFD84315),
        )
        "electronic" -> modern.copy(
            key = key, bg = Color.Black, text = Color(0xFF00FF00),
            cardBg = Color(0xFF001100), cardBorder = Color(0xFF003300),
            primary = Color(0xFF00FF00), primaryHover = Color(0xFF00CC00),
            navbarBg = Color(0xFF001100), navbarText = Color(0xFF00FF00),
            inputBg = Color.Black, inputText = Color(0xFF00FF00), inputBorder = Color(0xFF00FF00),
            font = Fonts.mono, radius = 0.dp, shadowElevation = 0.dp,
            visualizer = Color(0xFF00FF00), isDark = true,
        )
        "stylish" -> modern.copy(
            key = key, bg = Color.White, text = Color.Black,
            cardBg = Color.White, cardBorder = Color.Black,
            primary = Color.Black, primaryHover = Color(0xFF333333),
            navbarBg = Color.White, navbarText = Color.Black,
            inputBg = Color(0xFFF8F9FA), inputText = Color.Black, inputBorder = Color.Black,
            font = FontFamily.SansSerif, radius = 0.dp, shadowElevation = 0.dp,
            hardShadowOffset = 8.dp, hardShadowColor = Color(0x26000000), visualizer = Color.Black,
        )
        "business" -> modern.copy(
            key = key, bg = Color(0xFFF1F5F9), text = Color(0xFF1E293B),
            cardBg = Color.White, cardBorder = Color(0xFFCBD5E1),
            primary = Color(0xFF1E40AF), primaryHover = Color(0xFF1E3A8A),
            navbarBg = Color(0xFF1E3A8A), navbarText = Color.White,
            inputBg = Color.White, inputText = Color(0xFF1E293B), inputBorder = Color(0xFFCBD5E1),
            font = Fonts.roboto, radius = 4.dp, shadowElevation = 1.dp, visualizer = Color(0xFF1E40AF),
        )
        "material3" -> modern.copy(
            key = key, bg = Color(0xFFFFFBFE), text = Color(0xFF1C1B1F),
            cardBg = Color(0xFFF3EDF7), cardBorder = Color.Transparent,
            primary = Color(0xFF6750A4), primaryHover = Color(0xFF6750A4),
            navbarBg = Color(0xFFF3EDF7), navbarText = Color(0xFF1C1B1F),
            inputBg = Color(0xFFEADDFF), inputText = Color(0xFF1C1B1F), inputBorder = Color.Transparent,
            font = Fonts.roboto, radius = 16.dp, visualizer = Color(0xFF6750A4),
        )
        "material2" -> modern.copy(
            key = key, bg = Color(0xFFFAFAFA), text = Color(0xFF212121),
            cardBg = Color.White, cardBorder = Color.Transparent,
            primary = Color(0xFF6200EE), primaryHover = Color(0xFF3700B3),
            navbarBg = Color(0xFF6200EE), navbarText = Color.White,
            inputBg = Color(0xFFF5F5F5), inputText = Color.Black, inputBorder = Color.Transparent,
            font = Fonts.roboto, radius = 4.dp, shadowElevation = 2.dp, visualizer = Color(0xFF6200EE),
        )
        "classic" -> modern.copy(
            key = key, bg = Color.White, text = Color.Black,
            cardBg = Color.White, cardBorder = Color(0xFFDCDCDC),
            primary = Color(0xFF4D90FE), primaryHover = Color(0xFF357AE8),
            navbarBg = Color(0xFFF1F1F1), navbarText = Color.Black,
            inputBg = Color.White, inputText = Color.Black, inputBorder = Color(0xFFD9D9D9),
            font = FontFamily.SansSerif, radius = 2.dp, visualizer = Color(0xFF4D90FE),
        )
        else -> modern
    }
}

val LocalAppTheme = staticCompositionLocalOf { Themes.of("") }

object T {
    val c: AppTheme @Composable get() = LocalAppTheme.current
}

/** Bootstrap 5.3 の色 */
object Bs {
    val danger = Color(0xFFDC3545)
    val warning = Color(0xFFFFC107)
    val success = Color(0xFF198754)
    val info = Color(0xFF0DCAF0)
    val secondary = Color(0xFF6C757D)
    val dark = Color(0xFF212529)
    val light = Color(0xFFF8F9FA)
    val infoBg = Color(0xFFCFF4FC)
    val infoText = Color(0xFF055160)
    val warningBg = Color(0xFFFFF3CD)
    val warningText = Color(0xFF664D03)
    val successBg = Color(0xFFD1E7DD)
    val successText = Color(0xFF0A3622)
    val dangerText = Color(0xFFD33D53)
}
