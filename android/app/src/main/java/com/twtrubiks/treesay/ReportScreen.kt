package com.twtrubiks.treesay

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private sealed interface ReportState {
    data object Loading : ReportState

    data class Loaded(val result: WeeklyResult, val trees: List<Pair<LocalDate, Emotion?>>) : ReportState

    data class Failed(val error: TreeSayException) : ReportState
}

/** 整理週報要請 Claude 幫忙，還沒有 key 的時候說的話。 */
const val REPORT_NEEDS_KEY = "整理週報要請 Claude 幫忙，把這一週的日記整理成幾句話。先給樹一把 Claude API key。"

/**
 * 週報：森林朋友幫你寫的週記。週一到週日，過完的週才整理（生成一次後永久快取），
 * 這週還在生長中。預設打開最近一個已結束的週。
 */
@Composable
fun ReportScreen(onOpenForest: () -> Unit, needKey: (reason: String) -> Unit) {
    val container = LocalContext.current.container
    val today = TreeDay.effectiveDate(LocalDateTime.now())
    val thisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weeks = (0 until 8).map { thisWeek.minusWeeks(it.toLong()) }
    var selectedText by rememberSaveable { mutableStateOf(weeks[1].toString()) }
    val selected = LocalDate.parse(selectedText)
    var state by remember { mutableStateOf<ReportState>(ReportState.Loading) }

    LaunchedEffect(selected) {
        state = ReportState.Loading
        state = try {
            val result = container.report(selected)
            val trees = withContext(Dispatchers.IO) {
                val week = Weekly.dates(selected)
                // 森林清單也含沒收尾的日子，這排小樹只畫真的種下的
                val planted = setOf(YearMonth.from(week.start), YearMonth.from(week.endInclusive))
                    .flatMap { container.diary.month(it) }
                    .filter { it.status == DayStatus.PLANTED }
                    .associate { it.date to it.emotion }
                (0L until 7L).map { selected.plusDays(it) }.map { it to planted[it] }
            }
            ReportState.Loaded(result, trees)
        } catch (e: TreeSayException) {
            ReportState.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReportState.Failed(TreeSayException(Diary.TREE_ASLEEP, TreeSayException.Kind.ASLEEP))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text(weekLabel(selected), color = InkMute, fontSize = 15.sp)
            PageTitle("森林朋友幫你寫的週記", Modifier.padding(top = 2.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (week in weeks) {
                val chosen = week == selected
                val shape = RoundedCornerShape(14.dp)
                Text(
                    weekLabel(week) + if (week == thisWeek) "（本週）" else "",
                    Modifier
                        .clip(shape)
                        .background(if (chosen) Accent else Color.Transparent, shape)
                        .border(1.dp, if (chosen) Accent else Border, shape)
                        .clickable { selectedText = week.toString() }
                        .testTag("week_$week")
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (chosen) AccentContrast else InkSoft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        when (val s = state) {
            ReportState.Loading -> Column(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FlyingBird()
                Gap(18.dp)
                Text("樹正在幫你整理這一週……", color = InkSoft, fontSize = 16.sp)
            }
            is ReportState.Failed -> when (s.error.kind) {
                TreeSayException.Kind.KEY_MISSING, TreeSayException.Kind.KEY_INVALID ->
                    QuietCard(if (s.error.kind == TreeSayException.Kind.KEY_MISSING) REPORT_NEEDS_KEY else s.error.message.orEmpty()) {
                        Pill("連上森林") { needKey(REPORT_NEEDS_KEY) }
                    }
                else -> QuietCard(s.error.message ?: Diary.TREE_ASLEEP)
            }
            is ReportState.Loaded -> when (val result = s.result) {
                WeeklyResult.Growing -> QuietCard("這週還在生長中 🌱", "等這週過完，森林朋友會幫你整理。")
                WeeklyResult.Empty -> QuietCard("這一週是安靜的土壤。", "沒有種下樹的日子，也是好好過的日子。")
                is WeeklyResult.Ready -> {
                    ReportCard(result.report)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("這一週的樹", color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for ((date, emotion) in s.trees) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .background(BgCard, RoundedCornerShape(12.dp))
                                        .border(1.dp, BorderSoft, RoundedCornerShape(12.dp))
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("週${Format.weekday(date)}", color = InkMute, fontSize = 12.sp)
                                    Box(Modifier.height(36.dp), contentAlignment = Alignment.BottomCenter) {
                                        if (emotion != null) EmotionTree(emotion = emotion, width = 24.dp) else Soil(stirred = false)
                                    }
                                    Text("${date.dayOfMonth}", color = InkMute, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text("謝謝你，這一週也好好活著。", color = InkSoft, fontSize = 16.sp)
                        QuietLink("回森林看看 →", color = Accent, size = 15, onClick = onOpenForest)
                    }
                }
            }
        }
    }
}

private fun weekLabel(weekStart: LocalDate) = "${Format.shortDate(weekStart)} － ${Format.shortDate(weekStart.plusDays(6))}"

@Composable
private fun ReportCard(report: WeeklyReport) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Section("這週的好事", report.goodThings)
            if (report.badThings.isNotEmpty()) Section("辛苦的部分", report.badThings)
            Column {
                Text("這週的關鍵字", color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Gap(8.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in report.keywords) {
                        Text(
                            k,
                            Modifier
                                .background(BgBubbleTree, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            color = AccentSerif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            HorizontalDivider(color = BorderSoft)
            Text(report.advice, color = AccentSerif, fontSize = 18.sp, lineHeight = 32.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Section(title: String, items: List<String>) {
    Column {
        Text(title, color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Gap(6.dp)
        for (item in items) {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("・", color = Ink, fontSize = 17.sp)
                BodyText(item)
            }
        }
    }
}

/** 整理中：一隻小鳥慢慢飛（畫面安靜時停著）。 */
@Composable
private fun FlyingBird() {
    val quiet = LocalReduceMotion.current
    // 只在 graphicsLayer 裡讀，每一格只重畫
    val fly = if (quiet) {
        null
    } else {
        rememberInfiniteTransition(label = "fly").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1750, easing = EaseInOut), RepeatMode.Reverse), label = "fly",
        )
    }
    Canvas(
        Modifier
            .size(90.dp, 64.dp)
            .graphicsLayer {
                val t = fly?.value ?: 0f
                translationX = -16.dp.toPx() * t
                translationY = -20.dp.toPx() * t
                rotationZ = -8f * t
            },
    ) {
        val u = size.width / 90f
        drawOval(Color(0xFFDF8C6F), Offset(22 * u, 22 * u), Size(46 * u, 26 * u))
        rotate(-18f, pivot = Offset(20 * u, 24 * u)) {
            drawOval(Color(0xFFF1AA92), Offset(4 * u, 16 * u), Size(32 * u, 16 * u))
        }
        drawOval(Color(0xFFE1957A), Offset(54 * u, 14 * u), Size(18 * u, 18 * u))
    }
}
