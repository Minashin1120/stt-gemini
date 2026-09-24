package com.minashin1120.voxcribe.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.minashin1120.voxcribe.ui.theme.T
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * index.html startVisualizer の移植。
 * 波形（感度自動調整）＋絶対レベルバー、250msごとの入力レベル表示。
 */
@Composable
fun Visualizer(ctrl: WorkspaceController) {
    val t = T.c
    var frame by remember { mutableLongStateOf(0L) }
    val state = remember { VisState() }

    LaunchedEffect(ctrl.isRecording) {
        state.ema = 0.02f
        state.trails.clear()
        var lastLevel = 0L
        while (ctrl.isRecording) {
            withFrameNanos { now ->
                val data = ctrl.latestBlock
                if (data != null && data.isNotEmpty()) {
                    var peak = 0f
                    var sum = 0.0
                    for (d in data) {
                        val a = abs(d)
                        if (a > peak) peak = a
                        sum += d.toDouble() * d
                    }
                    val rms = sqrt(sum / data.size).toFloat()
                    state.ema = state.ema * 0.85f + max(peak, rms * 1.5f) * 0.15f
                    if (now - lastLevel > 250_000_000L) {
                        lastLevel = now
                        val dbfs = if (rms > 0) 20 * log10(rms) else Float.NEGATIVE_INFINITY
                        ctrl.levelText = when {
                            rms < 0.001f -> LevelText("入力レベル: ほぼ無音", 1)
                            rms < 0.012f -> LevelText("入力レベル: 小さめ (${dbfs.roundToInt()} dBFS・停止後に補正)", 2)
                            peak > 0.92f -> LevelText("入力レベル: 大きすぎます（端末を少し離してください）", 1)
                            else -> LevelText("入力レベル: 適正 (${dbfs.roundToInt()} dBFS)", 3)
                        }
                    }
                    var sens = 8f
                    if (state.ema > 0.02f) sens = min(12f, max(3f, 0.55f / state.ema))
                    else if (state.ema > 0.005f) sens = 10f
                    state.trails.add(0, data.copyOf() to sens)
                    while (state.trails.size > 4) state.trails.removeAt(state.trails.size - 1)
                }
                frame = now
            }
        }
    }

    val gaming = t.isGaming
    Canvas(
        Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(15.dp))
            .then(if (gaming) Modifier.border(1.dp, t.primary, RoundedCornerShape(15.dp)) else Modifier)
    ) {
        if (frame < 0L) return@Canvas
        drawRect(Color.Black.copy(alpha = if (gaming) 0.5f else 1f))
        val w = size.width
        val h = size.height
        val alphas = floatArrayOf(1f, 0.75f, 0.56f, 0.42f)
        state.trails.forEachIndexed { idx, (data, sens) ->
            val color = if (gaming) Color.hsv(((System.currentTimeMillis() / 10) % 360).toFloat(), 1f, 1f)
            else t.primary
            val path = Path()
            val sw = w / data.size
            var x = 0f
            for (i in data.indices) {
                var y = (data[i] * sens + 1f) * h / 2f
                if (y < 0) y = 0f
                if (y > h) y = h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                x += sw
            }
            drawPath(path, color.copy(alpha = alphas[idx]), style = Stroke(width = 2.dp.toPx()))
        }
        val ema = state.ema
        val levelW = min(w, max(2f, w * min(1f, ema * 4)))
        val barColor = when {
            ema < 0.04f -> Color(0xD9DC3545)
            ema < 0.15f -> Color(0xE6FFC107)
            else -> Color(0xD9198754)
        }
        drawRect(barColor, topLeft = Offset(0f, h - 4.dp.toPx()), size = Size(levelW, 4.dp.toPx()))
    }
}

private class VisState {
    var ema = 0.02f
    val trails = mutableListOf<Pair<FloatArray, Float>>()
}
