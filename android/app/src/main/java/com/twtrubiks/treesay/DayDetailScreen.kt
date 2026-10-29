package com.twtrubiks.treesay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 某一天的詳情。種下的看日記與樹的回覆，那天的隻字片語收在下面；
 * 沒收尾的日子那些話一樣看得見，在補種窗口內還能把它種下——語氣是「還來得及」，不是「你漏了」。
 */
@Composable
fun DayDetailScreen(
    date: LocalDate,
    onBack: () -> Unit,
    pendingPlant: LocalDate?,
    onPendingPlantTaken: () -> Unit,
    needKeyToPlant: (reason: String, date: LocalDate) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf<DayView?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var planting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMessages by rememberSaveable { mutableStateOf(false) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { container.diary.day(date) }
        day = loaded
        notFound = loaded == null
        planting = loaded?.status == DayStatus.PLANTING
    }

    suspend fun follow(id: java.util.UUID) {
        planting = true
        val failure = PlantWorker.await(context, id)
        reload()
        if (failure != null) {
            planting = false
            if (failure.kind == TreeSayException.Kind.KEY_MISSING || failure.kind == TreeSayException.Kind.KEY_INVALID) {
                needKeyToPlant(failure.message.orEmpty(), date)
            } else {
                error = failure.message
            }
        }
    }

    // 補種：那天忘了按，回來把它種下。過了窗口就不給按鈕
    fun plant() {
        if (planting) return
        if (container.keyStore.get() == null) {
            needKeyToPlant(PLANT_NEEDS_KEY, date)
            return
        }
        planting = true
        error = null
        scope.launch { follow(PlantWorker.start(context, date)) }
    }

    LaunchedEffect(date) {
        reload()
        if (day?.status == DayStatus.PLANTING) PlantWorker.running(context, date)?.let { follow(it) }
    }
    LaunchedEffect(pendingPlant, day) {
        if (pendingPlant == date && day != null) {
            onPendingPlantTaken()
            plant()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            QuietLink("← 回森林", color = Accent, size = 16, onClick = onBack)
            val d = day
            if (notFound) {
                Text("這一天還是安靜的土壤。", Modifier.padding(vertical = 40.dp), color = InkMute, fontSize = 17.sp)
            } else if (d != null) {
                Column {
                    Text(Format.dateLabel(d.date), color = InkMute, fontSize = 15.sp)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                        // 沒種下的日子不畫樹：頁面說「還沒種下」，上面卻有一棵樹會自相矛盾
                        if (d.status == DayStatus.PLANTED) {
                            EmotionTree(emotion = d.emotion, width = 40.dp, breathe = true)
                            HGap(10.dp)
                        }
                        d.emotion?.let { Text(it.label, color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                    }
                }

                if (d.status == DayStatus.PLANTED) {
                    Card { Text(d.diary.orEmpty(), color = Ink, fontSize = 17.sp, lineHeight = 32.sp) }
                    TreeReply { BodyText(d.treeReply.orEmpty()) }
                } else {
                    // 沒收尾的日子：那些話一樣要看得見，不是被 App 吞掉
                    FlowRow(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (d.canPlant || planting) "這一天的樹還沒種下——還來得及。" else "這一天的樹沒有種下。那些話還留在這裡。",
                            Modifier.align(Alignment.CenterVertically),
                            color = InkMute,
                            fontSize = 16.sp,
                        )
                        if (d.canPlant || planting) {
                            Pill(if (planting) "樹正在生長…" else "種下這一天的樹", filled = false, enabled = !planting, onClick = ::plant)
                        }
                    }
                }

                if (d.messages.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                        if (d.status == DayStatus.PLANTED) {
                            QuietLink(
                                if (showMessages) "收起那天的隻字片語 ︿" else "看看那天的隻字片語 ﹀",
                                Modifier.align(Alignment.Start),
                                color = InkSoft,
                                size = 15,
                            ) { showMessages = !showMessages }
                        }
                        if (showMessages || d.status != DayStatus.PLANTED) {
                            for (m in d.messages) {
                                Gap(10.dp)
                                UserBubble(m, Modifier.widthIn(max = 300.dp))
                                Text(Format.time(m.createdAt), Modifier.padding(top = 3.dp, end = 4.dp), color = InkMute, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        Toast(error) { error = null }
    }
}
