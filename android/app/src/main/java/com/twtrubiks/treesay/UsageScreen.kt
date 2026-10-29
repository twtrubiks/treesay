package com.twtrubiks.treesay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 費用：依官方定價估算每個月花了多少，實際金額以 Claude Console 的帳單為準。
 * 只按月加總、不拆到天，也不畫趨勢圖——它是工具，不是這個 App 想讓人每天看的東西。
 */
@Composable
fun UsageScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val diary = LocalContext.current.container.diary
    val usage by produceState<Result<UsageSummary>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { diary.usage() } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        QuietLink("← 回去", color = InkSoft, size = 16, onClick = onBack)
        Column {
            PageTitle("AI 費用")
            Gap(8.dp)
            Subtitle("種樹和週報都會呼叫 Claude API。這裡依官方定價估算每個月花了多少，實際金額以 Claude Console（platform.claude.com）的帳單為準。")
        }
        val result = usage
        val summary = result?.getOrNull()
        when {
            result == null -> QuietCard("樹正在算……")
            summary == null -> QuietCard(Diary.TREE_ASLEEP)
            summary.months.isEmpty() -> QuietCard("還沒有花過錢。", "種下第一棵樹之後，這裡才會有數字 🌱")
            else -> {
                for (m in summary.months) {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${m.month.year} 年 ${m.month.monthValue} 月", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(Format.usd(m.totalUsd), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "種樹 ${Format.usd(m.plantUsd)} · 週報 ${Format.usd(m.weeklyUsd)}",
                            Modifier.padding(top = 6.dp),
                            color = InkSoft,
                            fontSize = 14.sp,
                        )
                        if (m.unpricedCalls > 0) {
                            Text(
                                "另有 ${m.unpricedCalls} 次呼叫用的模型不在價目表上，沒有算進去",
                                Modifier.padding(top = 4.dp),
                                color = InkMute,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Text("到目前為止，一共約 ${Format.usd(summary.totalUsd)}", color = InkSoft, fontSize = 15.sp)
            }
        }
    }
}
