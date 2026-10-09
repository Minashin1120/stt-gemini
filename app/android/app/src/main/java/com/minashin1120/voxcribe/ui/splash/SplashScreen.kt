package com.minashin1120.voxcribe.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minashin1120.voxcribe.ui.theme.Fonts
import kotlin.math.PI
import kotlin.math.sin

private const val INTRO_MS = 1900
private const val EXIT_MS = 950
private val BAR_HEIGHTS = floatArrayOf(10f, 22f, 34f, 44f, 34f, 22f, 10f)

/** 区間 [a,b] に正規化した進行度（0..1） */
private fun seg(t: Float, a: Float, b: Float, e: Easing = FastOutSlowInEasing): Float = e.transform(((t - a) / (b - a)).coerceIn(0f, 1f))

private val BackOut = Easing { f ->
    val c = 1.70158f
    val x = f - 1f
    1f + (c + 1f) * x * x * x + c * x * x
}

/**
 * 起動時のスプラッシュ。ぼかしを多用した演出:
 * 背景のオーブが滲みながら漂い、ロゴがぼけた状態からピントが合い、波形バーが立ち上がり、
 * 波紋とともにワードマークがぼかしから浮かび上がる。最後は拡大しながらぼけて消える。
 * Modifier.blur は Android 12 (API 31) 以上で有効（それ未満はぼかし無しで同じ動き）。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val intro = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(INTRO_MS, easing = LinearEasing))
        exit.animateTo(1f, tween(EXIT_MS, easing = FastOutSlowInEasing))
        onFinished()
    }
    val t = intro.value * INTRO_MS / 1000f // 秒
    val x = exit.value

    // 退場: ロゴが手前に飛び出して画面外へ抜け、背景が徐々に薄れてアプリ画面が見えてくる
    val fly = seg(x, 0f, 0.85f, Easing { f -> f * f * f }) // 加速して飛び出す
    val textOut = 1f - seg(x, 0f, 0.3f)
    val bgAlpha = 1f - seg(x, 0.2f, 1f, LinearEasing)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().alpha(bgAlpha)
                .background(Brush.linearGradient(listOf(Color(0xFF0B1020), Color(0xFF0D1427), Color(0xFF0A1120))))
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 背景オーブ（強くぼかして漂わせる）
        val orbIn = seg(t, 0f, 0.8f) * (1f - seg(x, 0f, 0.6f))
        Orb(Color(0xFF6668FF), 300.dp, (-90 + 40 * sin(t * 1.3f)).dp, (-170 + 30 * sin(t * 1.1f + 1f)).dp, 0.55f * orbIn, 1.2f - 0.2f * orbIn)
        Orb(Color(0xFF2CB9A8), 280.dp, (100 + 36 * sin(t * 1.2f + 2f)).dp, (190 + 34 * sin(t * 0.9f)).dp, 0.5f * orbIn, 1.2f - 0.2f * orbIn)
        Orb(Color(0xFFB07CFF), 200.dp, (70 + 30 * sin(t * 1.5f + 4f)).dp, (-60 + 30 * sin(t * 1.0f + 2f)).dp, 0.32f * orbIn, 1f)

        // 波紋
        Canvas(Modifier.fillMaxSize().alpha(textOut)) {
            val c = Offset(size.width / 2f, size.height / 2f - 28.dp.toPx())
            for (k in 0 until 3) {
                val p = seg(t, 0.55f + 0.18f * k, 1.45f + 0.18f * k)
                if (p > 0f && p < 1f) {
                    drawCircle(
                        Color(0xFF9B9EFF).copy(alpha = 0.45f * (1f - p)),
                        radius = 56.dp.toPx() + 150.dp.toPx() * p,
                        center = c,
                        style = Stroke(width = (3f * (1f - p) + 0.5f).dp.toPx())
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-28).dp)) {
            val tileOut = 1f - seg(x, 0.6f, 1f, LinearEasing)
            // ロゴタイル: ぼけた状態から拡大しつつピントが合う
            val tileP = seg(t, 0f, 0.55f, BackOut)
            val tileA = seg(t, 0f, 0.3f)
            Box(
                Modifier.size(112.dp)
                    .blur((28f * (1f - seg(t, 0f, 0.6f)) + 10f * fly).dp, BlurredEdgeTreatment.Unbounded)
                    .alpha(tileA * tileOut)
                    .scale((0.55f + 0.45f * tileP) * (1f + 15f * fly))
                    .shadow(28.dp, RoundedCornerShape(32.dp), ambientColor = Color(0xFF5B5CE2), spotColor = Color(0xFF5B5CE2))
                    .clip(RoundedCornerShape(32.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7374F5), Color(0xFF4B4CCC))))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val u = size.width / 108f
                    BAR_HEIGHTS.forEachIndexed { i, h ->
                        val rise = seg(t, 0.2f + 0.05f * i, 0.7f + 0.05f * i, BackOut)
                        // 立ち上がった後は波打つ
                        val wave = if (t > 0.9f) 1f + 0.28f * sin(2f * PI.toFloat() * (t * 1.1f - i * 0.1f)) * seg(t, 0.9f, 1.3f) else 1f
                        val hh = h * 1.35f * rise * wave * u
                        val cx = (30f + 8f * i) * u
                        drawRoundRect(
                            Color.White,
                            topLeft = Offset(cx - 2.5f * u, size.height / 2f - hh / 2f),
                            size = Size(5f * u, hh.coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(2.5f * u)
                        )
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            // ワードマーク: ぼかし→シャープ、字間が詰まる
            val wp = seg(t, 0.5f, 1.0f)
            Text(
                "Voxcribe",
                color = Color(0xFFF5F7FF), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Fonts.inter,
                letterSpacing = (10f - 10.5f * wp).sp,
                modifier = Modifier.offset(y = (14f * (1f - wp)).dp).alpha(wp * textOut).blur((18f * (1f - wp)).dp, BlurredEdgeTreatment.Unbounded)
            )
            Spacer(Modifier.height(8.dp))
            val sp2 = seg(t, 0.8f, 1.3f)
            Text(
                "AI TRANSCRIPTION",
                color = Color(0xFF9BA3BC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (8f - 6.7f * sp2).sp,
                modifier = Modifier.alpha(sp2 * textOut).blur((10f * (1f - sp2)).dp, BlurredEdgeTreatment.Unbounded)
            )
        }
        }
    }
}

@Composable
private fun Orb(color: Color, size: Dp, x: Dp, y: Dp, alpha: Float, scale: Float) {
    Box(
        Modifier.offset(x, y).size(size).scale(scale).alpha(alpha.coerceIn(0f, 1f))
            .blur(60.dp, BlurredEdgeTreatment.Unbounded)
            .background(Brush.radialGradient(listOf(color, Color.Transparent)), RoundedCornerShape(50))
    )
}
