package com.twtrubiks.treesay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 樹記得的事。每一條看得到、刪得掉，刪了不會復活——看不見的記憶比沒有記憶更可怕。
 * 兩段式放下：第一按只是問一聲，再按一次才真的放下，沒有跳出視窗的驚嚇。
 */
@Composable
fun MemoryScreen() {
    val diary = LocalContext.current.container.diary
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<MemoryView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            memories = withContext(Dispatchers.IO) { diary.memories() }
        } catch (e: Exception) {
            failed = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            PageTitle("樹記得的事")
            Gap(8.dp)
            Subtitle("種樹的時候，樹會順手記下一些關於你的事，讓它更懂你。哪一件不想被記得，隨時可以讓它放下。")
        }
        val list = memories
        when {
            failed -> QuietCard(Diary.TREE_ASLEEP)
            list == null -> QuietCard("樹正在想……")
            list.isEmpty() -> QuietCard("樹還沒記下什麼。", "多說幾天話，它會慢慢認識你 🌱")
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (m in list) {
                    val asking = confirming == m.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(BgCard, RoundedCornerShape(18.dp))
                            .border(1.dp, Border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BodyText(m.content)
                            Text("${Format.shortDate(m.sourceDate)}記下", Modifier.padding(top = 4.dp), color = InkMute, fontSize = 13.sp)
                        }
                        HGap(12.dp)
                        val shape = RoundedCornerShape(12.dp)
                        Text(
                            if (asking) "再按一次，就放下" else "放下",
                            Modifier
                                .clip(shape)
                                .background(if (asking) BgBubbleTree else Color.Transparent, shape)
                                .border(1.dp, if (asking) Accent else Border, shape)
                                .clickable {
                                    if (!asking) {
                                        confirming = m.id
                                    } else {
                                        confirming = null
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { diary.letGo(m.id) }
                                            } catch (e: TreeSayException) {
                                                // 已經放下了：一樣從清單拿掉
                                            }
                                            memories = memories?.filter { it.id != m.id }
                                        }
                                    }
                                }
                                .testTag("let_go")
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            color = if (asking) AccentSerif else InkMute,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
