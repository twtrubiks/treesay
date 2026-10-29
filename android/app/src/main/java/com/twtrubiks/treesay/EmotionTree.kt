package com.twtrubiks.treesay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 圓圈樹的三片葉：大葉、左小葉、右小葉。 */
data class TreeColors(val c1: Color, val c2: Color, val c3: Color)

/** 7 種情緒的顏色，取自網頁版 `emotions.ts`（oklch 換算成 sRGB）。 */
val EMOTION_COLORS: Map<Emotion, TreeColors> = mapOf(
    Emotion.HAPPY to TreeColors(Color(0xFFE4B750), Color(0xFFF6D389), Color(0xFFCD9C1F)),
    Emotion.CALM to TreeColors(Color(0xFF8AC4C0), Color(0xFFA9D9D5), Color(0xFF68ADA8)),
    Emotion.EXCITED to TreeColors(Color(0xFFF0A08C), Color(0xFFFDBCAB), Color(0xFFDE826A)),
    Emotion.TIRED to TreeColors(Color(0xFFA4A1BD), Color(0xFFBDBBD0), Color(0xFF8B87A9)),
    Emotion.SAD to TreeColors(Color(0xFF8CB2D9), Color(0xFFACC7E4), Color(0xFF6C96C2)),
    Emotion.ANXIOUS to TreeColors(Color(0xFFB9A1BF), Color(0xFFD0BCD5), Color(0xFFA387A9)),
    Emotion.ANGRY to TreeColors(Color(0xFFD78E8D), Color(0xFFE8ACAB), Color(0xFFC66F70)),
)

/** 還沒判定情緒時的中性生長樹。 */
val NEUTRAL_TREE = TreeColors(Color(0xFF80AC77), Color(0xFF9DC494), Color(0xFF6E9A65))

/**
 * 四季的中性樹，取自網頁版 `season.ts`。大葉一律留在綠色系，季節的顏色只放在小葉上：
 * 情緒色票裡沒有綠，整棵橘的秋天會被讀成「期待」，還沒說話就像被判了心情。
 */
val SEASON_TREE: Map<Season, TreeColors> = mapOf(
    Season.SPRING to TreeColors(Color(0xFFA0C274), Color(0xFFC7D69B), Color(0xFFF2BCD4)),
    Season.SUMMER to TreeColors(Color(0xFF60A563), Color(0xFF85BC79), Color(0xFF3D8E53)),
    Season.AUTUMN to TreeColors(Color(0xFF9FAD63), Color(0xFFE8B960), Color(0xFFC86D41)),
    Season.WINTER to TreeColors(Color(0xFF94AC98), Color(0xFFE6ECEF), Color(0xFF779580)),
)

private val MOONLIGHT = Color(0xFFC7DBEA)

/** 入夜：葉色沉一點、冷一點，像月光照著（CSS 的 saturate(0.78) brightness(0.9)）。 */
private fun Color.night(): Color {
    val s = 0.78f
    val r = (0.213f + 0.787f * s) * red + (0.715f - 0.715f * s) * green + (0.072f - 0.072f * s) * blue
    val g = (0.213f - 0.213f * s) * red + (0.715f + 0.285f * s) * green + (0.072f - 0.072f * s) * blue
    val b = (0.213f - 0.213f * s) * red + (0.715f - 0.715f * s) * green + (0.072f + 0.928f * s) * blue
    return Color((r * 0.9f).coerceIn(0f, 1f), (g * 0.9f).coerceIn(0f, 1f), (b * 0.9f).coerceIn(0f, 1f), alpha)
}

private class Drifter(
    val kind: Visitor,
    val bornAt: Long,
    val durationMs: Long,
    // 起點：樹寬的比例、離地的高度（設計稿 px，乘上 k）
    val left: Float,
    val bottom: Float,
    val side: Float,
    val dist: Float,
    val sway: Float,
    val k: Float,
    val phaseMs: Long,
)

/**
 * 設計稿的圓圈樹：樹幹＋三顆圓葉，依情緒上色；未判定時是中性綠，[seasonal] 時隨季節與入夜換樣貌。
 * 跟網頁版 `EmotionTree.vue` 同一套動作：
 * - [idle]：風。不是鐘擺，被推過去、停一下、再補一下，然後慢慢回正——只是「它在這裡」，不帶任何狀態意義
 * - [breathe]：整個樹冠從樹幹頂端一起脹縮
 * - 來訪的小東西只跟著季節來（要同時開 [idle] 與 [seasonal]）。有情緒的樹不來訪：
 *   情緒樹掉的會是自己的葉子，難過那天的樹一直落葉會被讀成凋零
 */
@Composable
fun EmotionTree(
    modifier: Modifier = Modifier,
    emotion: Emotion? = null,
    width: Dp = 28.dp,
    breathe: Boolean = false,
    idle: Boolean = false,
    seasonal: Boolean = false,
) {
    val reduceMotion = LocalReduceMotion.current
    val sky = LocalSky.current
    val seasonOn = seasonal && emotion == null
    val night = seasonOn && sky.night
    val base = emotion?.let(EMOTION_COLORS::getValue) ?: if (seasonal) SEASON_TREE.getValue(sky.season) else NEUTRAL_TREE
    val target = if (night) TreeColors(base.c1.night(), base.c2.night(), base.c3.night()) else base
    // 換季與入夜都慢慢過去。只給季節樹：情緒色要是也慢慢變，中途的混色會被讀成別的情緒
    val spec = tween<Color>(if (seasonOn) 1200 else 0)
    val c1 by animateColorAsState(target.c1, spec, label = "c1")
    val c2 by animateColorAsState(target.c2, spec, label = "c2")
    val c3 by animateColorAsState(target.c3, spec, label = "c3")

    // 動畫的值只在畫的時候讀（State 傳進 Canvas），每一格只重畫、不重新組畫面——
    // 在組畫面時讀的話，整棵樹每秒重組 60 次，費電，UiAutomator 也永遠等不到畫面靜止
    val windy = idle && !reduceMotion
    val transition = rememberInfiniteTransition(label = "tree")
    val still = remember { mutableFloatStateOf(0f) }
    val root = if (windy) transition.gust(GUST_ROOT, delayMs = 0) else still
    // 樹冠比樹幹軟，晚 0.35 秒跟上；三片葉各自以互不整除的週期微顫，整棵樹不會每 9 秒重播一次
    val crown = if (windy) transition.gust(GUST_CROWN, delayMs = 350) else still
    val flutter1 = if (windy) transition.flutter(3700, 0) else still
    val flutter2 = if (windy) transition.flutter(4300, 1100) else still
    val flutter3 = if (windy) transition.flutter(5100, 2400) else still
    val breath = if (breathe && !reduceMotion) {
        transition.animateFloat(
            1f, 1.055f,
            infiniteRepeatable(tween(2500, easing = EaseInOut), RepeatMode.Reverse),
            label = "breathe",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    // 來訪者：先隨機等 6～14 秒，之後每 40～100 秒一次，都對準下一陣風最大的那一刻出發——是風把它們吹下來的
    val visiting = windy && seasonOn
    val drifters = remember { mutableStateListOf<Drifter>() }
    var frame by remember { mutableLongStateOf(0L) }
    val k = 0.6f + 0.4f * (width.value / 28f)
    LaunchedEffect(visiting, sky) {
        drifters.clear()
        if (!visiting) return@LaunchedEffect
        val idleStart = System.currentTimeMillis()
        var wait = 6_000L + Random.nextLong(8_000)
        while (isActive) {
            delay(wait)
            val phase = (System.currentTimeMillis() - idleStart) % GUST_MS
            delay((GUST_PEAK_MS - phase + GUST_MS) % GUST_MS)
            val kind = Seasons.visitorFor(sky)
            val count = 2 + Random.nextInt(2)
            repeat(count) { i ->
                if (i > 0) delay(320L + Random.nextLong(260))
                val y = (11 + Random.nextFloat() * 12) * k
                val dir = if (kind == Visitor.FIREFLY && Random.nextBoolean()) 1 else -1
                drifters += Drifter(
                    kind = kind,
                    bornAt = System.currentTimeMillis(),
                    durationMs = (kind.driftSeconds * (0.85f + Random.nextFloat() * 0.3f) * 1000).toLong(),
                    left = 0.28f + Random.nextFloat() * 0.46f,
                    bottom = y,
                    side = dir * (5 + Random.nextFloat() * 5) * k,
                    dist = if (kind.rise) -(20 + Random.nextFloat() * 8) * k else y,
                    sway = (1.5f + Random.nextFloat() * 1.5f) * k * 0.7f,
                    k = k,
                    phaseMs = Random.nextLong(3000),
                )
            }
            wait = 40_000L + Random.nextLong(60_000)
        }
    }
    LaunchedEffect(drifters.isNotEmpty()) {
        while (drifters.isNotEmpty()) {
            withFrameMillis { }
            frame = System.currentTimeMillis()
            drifters.removeAll { frame - it.bornAt > it.durationMs + 100 }
        }
    }

    Canvas(modifier.size(width, width * 34f / 28f)) {
        val s = size.width / 28f
        val w = size.width
        val h = size.height
        rotate(root.value, pivot = Offset(w / 2, h)) {
            drawRoundRect(
                Trunk,
                topLeft = Offset(w / 2 - 2 * s, h - 12 * s),
                size = Size(4 * s, 12 * s),
                cornerRadius = CornerRadius(2 * s),
            )
            // 樹冠最低點落在離地 9/34 處，風與呼吸都從那裡（約樹幹頂端）施力
            val crownPivot = Offset(w / 2, h * 0.735f)
            rotate(crown.value, pivot = crownPivot) {
                scale(breath.value, pivot = crownPivot) {
                    leaf(Offset(w / 2, h - 19 * s), 10 * s, c1, flutter1.value, night)
                    leaf(Offset(0.33f * w + 7 * s, h - 20 * s), 7 * s, c2, flutter2.value, night)
                    leaf(Offset(0.56f * w + 6 * s, h - 20 * s), 6 * s, c3, flutter3.value, night)
                }
            }
        }
        val t = frame
        for (d in drifters) drawDrifter(d, t, w, h)
    }
}

/** 一片葉：從底部微顫，旋轉變成葉尖的小位移，不是葉子在滑。 */
private fun DrawScope.leaf(center: Offset, radius: Float, color: Color, flutter: Float, night: Boolean) {
    rotate(flutter, pivot = Offset(center.x, center.y + radius)) {
        if (night) {
            drawCircle(
                Brush.radialGradient(
                    listOf(MOONLIGHT.copy(alpha = 0.3f), Color.Transparent),
                    center = center,
                    radius = radius * 1.35f,
                ),
                radius = radius * 1.35f,
                center = center,
            )
        }
        drawCircle(color, radius, center)
    }
}

private val FALL = CubicBezierEasing(0.35f, 0.1f, 0.7f, 1f)

private fun DrawScope.drawDrifter(d: Drifter, now: Long, w: Float, h: Float) {
    val p = ((now - d.bornAt).toFloat() / d.durationMs).coerceIn(0f, 1f)
    val eased = when {
        d.kind == Visitor.SNOW -> LinearEasing.transform(p)
        d.kind.rise -> EaseOut.transform(p)
        else -> FALL.transform(p)
    }
    // 淡入、停留、到點前淡掉
    val (peakAt, holdAt, hold) = if (d.kind.rise) Triple(0.12f, 0.75f, 0.8f) else Triple(0.10f, 0.82f, 0.9f)
    var alpha = when {
        p < peakAt -> p / peakAt * 0.95f
        p < holdAt -> 0.95f + (hold - 0.95f) * (p - peakAt) / (holdAt - peakAt)
        else -> hold * (1 - (p - holdAt) / (1 - holdAt))
    }
    val elapsed = now - d.bornAt + d.phaseMs
    val swayPeriod = when (d.kind) {
        Visitor.SNOW -> 2400f
        Visitor.FLUFF -> 2800f
        Visitor.FIREFLY -> 1100f
        Visitor.LEAF -> 1400f
        Visitor.PETAL -> 1600f
    }
    val swayX = d.sway * sin(2 * PI * elapsed / (swayPeriod * 2)).toFloat()
    if (d.kind == Visitor.FIREFLY) {
        alpha *= 0.25f + 0.75f * (0.5f + 0.5f * sin(2 * PI * elapsed / 2400.0).toFloat())
    }
    val x = d.left * w + d.side * eased * density + swayX * density
    val y = h - d.bottom * density + d.dist * eased * density
    val k = d.k * density
    translate(x, y) {
        when (d.kind) {
            Visitor.SNOW -> drawCircle(Color(0xFFF2F6F7).copy(alpha = alpha), 2 * k, Offset.Zero)
            Visitor.FLUFF -> drawCircle(Color(0xFFF1EEE6).copy(alpha = alpha * 0.9f), 2.5f * k, Offset.Zero)
            Visitor.FIREFLY -> {
                drawCircle(Color(0xFFFBDD67).copy(alpha = alpha * 0.4f), 4f * k, Offset.Zero)
                drawCircle(Color(0xFFFBDD67).copy(alpha = alpha), 1.5f * k, Offset.Zero)
            }
            Visitor.PETAL, Visitor.LEAF -> {
                val turn = if (d.kind == Visitor.LEAF) -360f * elapsed / 3000f else 360f * elapsed / 2600f
                rotate(turn % 360f, pivot = Offset.Zero) {
                    if (d.kind == Visitor.PETAL) {
                        drawOval(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFECEE4), Color(0xFFFDB0D4)),
                                startX = -3 * k,
                                endX = 3 * k,
                            ),
                            topLeft = Offset(-3 * k, -2 * k),
                            size = Size(6 * k, 4 * k),
                            alpha = alpha,
                        )
                    } else {
                        drawRoundRect(
                            Color(0xFFD77E49).copy(alpha = alpha),
                            topLeft = Offset(-3.5f * k, -2.25f * k),
                            size = Size(7 * k, 4.5f * k),
                            cornerRadius = CornerRadius(3 * k),
                        )
                    }
                }
            }
        }
    }
}

private const val GUST_MS = 9_000L
private const val GUST_PEAK_MS = 4_500L

// 風只從右邊來，所以只往左彎（負值是逆時針）。根部彎得少，樹冠從樹幹頂再多彎一截
private val GUST_ROOT = floatArrayOf(0f, -3.6f, -2.2f, -4.2f, 0.9f, 0f)
private val GUST_CROWN = floatArrayOf(0f, -7.5f, -4.5f, -9f, 2.1f, 0f)

@Composable
private fun InfiniteTransition.gust(values: FloatArray, delayMs: Int): State<Float> = animateFloat(
    0f, 0f,
    infiniteRepeatable(
        keyframes {
            durationMillis = GUST_MS.toInt()
            values[0] at 0 using EaseInOut
            values[1] at 1980 using EaseInOut
            values[2] at 3060 using EaseInOut
            values[3] at 4140 using EaseInOut
            values[4] at 7200 using EaseInOut
            values[5] at 9000
        },
        initialStartOffset = StartOffset(delayMs, StartOffsetType.Delay),
    ),
    label = "gust",
)

@Composable
private fun InfiniteTransition.flutter(periodMs: Int, aheadMs: Int): State<Float> = animateFloat(
    1.6f, -2.4f,
    infiniteRepeatable(
        tween(periodMs / 2, easing = EaseInOut),
        RepeatMode.Reverse,
        initialStartOffset = StartOffset(aheadMs, StartOffsetType.FastForward),
    ),
    label = "flutter",
)
