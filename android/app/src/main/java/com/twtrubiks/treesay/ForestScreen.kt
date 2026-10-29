package com.twtrubiks.treesay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private val MONTH_NAMES = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
private val WEEKDAY_NAMES = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 森林：一個月的樹。種下的樹依情緒上色；留了話但沒種下的日子只是土壤被翻動過的痕跡，
 * 刻意跟空白日只差一點——它是回去看看的入口，不是月曆上的一筆待辦。
 */
@Composable
fun ForestScreen(onOpenDay: (LocalDate) -> Unit) {
    val diary = LocalContext.current.container.diary
    val thisMonth = YearMonth.from(TreeDay.effectiveDate(LocalDateTime.now()))
    var monthText by rememberSaveable { mutableStateOf(thisMonth.toString()) }
    val month = YearMonth.parse(monthText)
    val days by produceState<Result<List<ForestDay>>?>(null, monthText) {
        value = withContext(Dispatchers.IO) { runCatching { diary.month(month) } }
    }
    val loaded = days?.getOrNull().orEmpty()
    // 只數真的種下的樹——沒收尾的日子也在清單裡，但它們不是樹
    val trees = loaded.count { it.status == DayStatus.PLANTED }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietLink("‹", size = 26) { monthText = month.minusMonths(1).toString() }
            PageTitle("${MONTH_NAMES[month.monthValue - 1]}月的森林", Modifier.padding(horizontal = 4.dp))
            Box(Modifier.alpha(if (month < thisMonth) 1f else 0f)) {
                QuietLink("›", size = 26) { if (month < thisMonth) monthText = month.plusMonths(1).toString() }
            }
        }
        Text(
            when {
                days?.isFailure == true -> Diary.TREE_ASLEEP
                trees > 0 -> "這個月，你在這裡留下了 $trees 棵樹。"
                else -> "這個月的土壤還很安靜。"
            },
            Modifier.padding(start = 6.dp, top = 2.dp),
            color = InkSoft,
            fontSize = 16.sp,
        )
        Gap(12.dp)
        FlowRow(
            Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (emotion in Emotion.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(EMOTION_COLORS.getValue(emotion).c1, CircleShape))
                    HGap(5.dp)
                    Text(emotion.label, color = InkSoft, fontSize = 13.sp)
                }
            }
        }
        Gap(18.dp)

        // 只是讓人讀得出哪一格是星期幾，所以比格子裡的日期還淡；週末不特別標色——森林裡沒有哪一天比較重要
        Row(Modifier.fillMaxWidth()) {
            for (name in WEEKDAY_NAMES) {
                Text(name, Modifier.weight(1f), color = InkMute, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        Gap(6.dp)
        val byDate = loaded.associateBy { it.date }
        val leading = month.atDay(1).dayOfWeek.value % 7
        val cells = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        for (week in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f)) {
                        if (date != null) DayCell(date, byDate[date], onOpenDay)
                    }
                }
            }
        }

        Gap(18.dp)
        // 空著的格子最容易被讀成待辦。這行字常駐、不指名任何一天——只在有空格時出現的話，它就變成「你漏了」的提示
        Text(
            "空著的格子不用補。想起哪一天的事，今天說給樹聽就好 🌱",
            Modifier.padding(horizontal = 6.dp),
            color = InkMute,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun DayCell(date: LocalDate, day: ForestDay?, onOpenDay: (LocalDate) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val planted = day?.status == DayStatus.PLANTED
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(shape)
            .background(BgCard, shape)
            .border(1.dp, if (day != null) Border else BorderSoft, shape)
            .then(if (day != null) Modifier.clickable { onOpenDay(date) } else Modifier)
            .testTag("day_$date")
            .padding(5.dp),
    ) {
        Text("${date.dayOfMonth}", color = InkMute, fontSize = 11.sp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            if (planted) {
                EmotionTree(emotion = day.emotion, width = 24.dp)
            } else {
                Soil(stirred = day != null)
            }
        }
    }
}

/** 空白日是一圈虛線的土；留了話沒種下的，土壤被翻動過，顏色深一點點。 */
@Composable
fun Soil(stirred: Boolean) {
    Box(
        Modifier
            .padding(bottom = 4.dp)
            .size(18.dp, 7.dp)
            .drawBehind {
                if (stirred) {
                    drawOval(InkFaint.copy(alpha = 0.55f), size = Size(size.width, size.height))
                } else {
                    drawOval(
                        InkFaint,
                        size = Size(size.width, size.height),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
                    )
                }
            },
    )
}
