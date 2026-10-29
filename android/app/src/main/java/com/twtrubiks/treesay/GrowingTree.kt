package com.twtrubiks.treesay

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/**
 * 種樹畫面那棵正在長的大樹：光暈與三片樹冠慢慢呼吸、火花往上飄（網頁版的 `.grow-tree`）。
 * 畫面安靜時不呼吸、火花收起來——停在半空的三個點看起來只是卡住了。
 */
@Composable
fun GrowingTree(modifier: Modifier = Modifier) {
    // 動畫的值只在畫的時候讀：每一格只重畫，不重新組畫面
    val quiet = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "grow")
    val still = remember { mutableFloatStateOf(1f) }
    val glow = if (quiet) still else transition.breathe(0)
    val b1 = if (quiet) still else transition.breathe(0)
    val b2 = if (quiet) still else transition.breathe(300)
    val b3 = if (quiet) still else transition.breathe(600)
    val sparks = if (quiet) {
        emptyList()
    } else {
        listOf(transition.rise(3000, 0), transition.rise(3400, 800), transition.rise(3800, 1600))
    }

    Canvas(modifier.size(260.dp, 204.dp)) {
        val u = size.width / 280f
        val w = size.width
        val h = size.height
        val glowCenter = Offset(w / 2, (20 + 100) * u)
        scale(glow.value, pivot = glowCenter) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0x80FFBC9D), Color.Transparent), glowCenter, 100 * u),
                radius = 100 * u,
                center = glowCenter,
            )
        }
        drawRoundRect(Color(0xFF634632), Offset(w / 2 - 8 * u, h - 70 * u), Size(16 * u, 70 * u), CornerRadius(6 * u))
        fun crown(color: Color, diameter: Float, left: Float, bottom: Float, breath: Float) {
            val center = Offset(left + diameter * u / 2, h - (bottom + diameter / 2) * u)
            scale(breath, pivot = center) { drawCircle(color, diameter * u / 2, center) }
        }
        crown(Color(0xFFF0A08C), 110f, w / 2 - 55 * u, 56f, b1.value)
        crown(Color(0xFFFDBCAB), 76f, 0.28f * w, 78f, b2.value)
        crown(Color(0xFFDE826A), 64f, 0.56f * w, 86f, b3.value)
        val starts = listOf(Triple(0.30f, 120f, 6f), Triple(0.60f, 130f, 5f), Triple(0.48f, 110f, 5f))
        sparks.forEachIndexed { i, rise ->
            val p = rise.value
            val (left, bottom, d) = starts[i]
            val r = d * u / 2 * (1 - 0.5f * p)
            drawCircle(
                Color(0xFFF6E2C0).copy(alpha = 0.85f * (1 - p)),
                r,
                Offset(left * w + d * u / 2, h - (bottom + d / 2) * u - 90 * u * p),
            )
        }
    }
}

/** 一律緩慢、有機：5 秒一次，脹到 1.035 倍。 */
@Composable
private fun InfiniteTransition.breathe(delayMs: Int): State<Float> = animateFloat(
    1f, 1.035f,
    infiniteRepeatable(tween(2500, easing = EaseInOut), RepeatMode.Reverse, StartOffset(delayMs)),
    label = "breathe",
)

@Composable
private fun InfiniteTransition.rise(durationMs: Int, delayMs: Int): State<Float> = animateFloat(
    0f, 1f,
    infiniteRepeatable(tween(durationMs, easing = EaseIn), RepeatMode.Restart, StartOffset(delayMs)),
    label = "rise",
)
