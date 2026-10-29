package com.twtrubiks.treesay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

/** 畫面。最底下的四個（首頁、森林、週報、記憶）是主導覽，其餘從它們往裡走。 */
private sealed interface Route {
    data object Today : Route

    data object Forest : Route

    data object Report : Route

    data object Memories : Route

    data object Settings : Route

    data object Usage : Route

    data class Day(val date: LocalDate) : Route

    /** 請使用者給一把 key。[plantAfter]：給完回去接著種那一天的樹。 */
    data class Key(val reason: String?, val plantAfter: LocalDate?) : Route
}

private val TABS = listOf(Route.Today to "首頁", Route.Forest to "森林", Route.Report to "週報", Route.Memories to "記憶")

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Today -> "today"
                Route.Forest -> "forest"
                Route.Report -> "report"
                Route.Memories -> "memories"
                Route.Settings -> "settings"
                Route.Usage -> "usage"
                is Route.Day -> "day|${it.date}"
                is Route.Key -> "key|${it.plantAfter ?: ""}|${it.reason ?: ""}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            when (parts[0]) {
                "forest" -> Route.Forest
                "report" -> Route.Report
                "memories" -> Route.Memories
                "settings" -> Route.Settings
                "usage" -> Route.Usage
                "day" -> Route.Day(LocalDate.parse(parts[1]))
                "key" -> Route.Key(parts[2].ifEmpty { null }, parts[1].ifEmpty { null }?.let(LocalDate::parse))
                else -> Route.Today
            }
        }
    },
)

/**
 * 整個 App。一打開就是首頁，沒有 key 也能先寫——白天丟訊息不需要 AI；
 * 按下種樹、打開週報要請 Claude 幫忙時，才請使用者給一把 key。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun TreeSayApp() {
    val container = LocalContext.current.container
    var profiles by remember { mutableStateOf(Purpose.entries.associateWith(container.settings::profile)) }
    var motion by remember { mutableStateOf(container.motion.get()) }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Today)) }
    var pendingPlant by rememberSaveable { mutableStateOf<String?>(null) }
    // key 存在 Keystore 裡，每換一頁重讀一次：在別的地方存了或清掉（例如種樹時才給的 key），回來都對得上
    var keyChanges by remember { mutableIntStateOf(0) }
    val apiKey = remember(stack, keyChanges) { container.keyStore.get() }
    // 整個畫面共用一份天色：每分鐘對一次，樹和種樹按鈕同一刻入夜
    val sky by produceState(Seasons.skyAt(LocalDateTime.now())) {
        while (true) {
            delay(60_000)
            value = Seasons.skyAt(LocalDateTime.now())
        }
    }

    val route = stack.last()
    fun push(next: Route) {
        stack = stack + next
    }
    fun pop() {
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(Route.Today)
    }
    fun needKeyToPlant(reason: String, date: LocalDate) = push(Route.Key(reason, date))
    val pending = pendingPlant?.let(LocalDate::parse)

    if (stack.size == 1 && route != Route.Today) BackHandler { stack = listOf(Route.Today) }

    TreeSayTheme {
        CompositionLocalProvider(
            LocalReduceMotion provides container.motion.reduceMotion(motion),
            LocalSky provides sky,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(BgApp)
                    .safeDrawingPadding()
                    // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                    .semantics { testTagsAsResourceId = true },
            ) {
                Box(Modifier.weight(1f)) {
                    when (route) {
                        Route.Today -> TodayScreen(
                            onOpenDay = { push(Route.Day(it)) },
                            onOpenSettings = { push(Route.Settings) },
                            pendingPlant = pending,
                            onPendingPlantTaken = { pendingPlant = null },
                            needKeyToPlant = ::needKeyToPlant,
                        )
                        Route.Forest -> ForestScreen(onOpenDay = { push(Route.Day(it)) })
                        Route.Report -> ReportScreen(
                            onOpenForest = { stack = listOf(Route.Forest) },
                            needKey = { push(Route.Key(it, null)) },
                        )
                        Route.Memories -> MemoryScreen()
                        Route.Usage -> UsageScreen(onBack = ::pop)
                        is Route.Day -> DayDetailScreen(
                            date = route.date,
                            onBack = ::pop,
                            pendingPlant = pending,
                            onPendingPlantTaken = { pendingPlant = null },
                            needKeyToPlant = ::needKeyToPlant,
                        )
                        is Route.Key -> KeySetupScreen(
                            reason = route.reason,
                            replacing = apiKey != null,
                            onSaved = {
                                container.keyStore.save(it)
                                keyChanges++
                                pop()
                                pendingPlant = route.plantAfter?.toString()
                            },
                            onCancel = ::pop,
                        )
                        Route.Settings -> SettingsScreen(
                            apiKey = apiKey,
                            profiles = profiles,
                            onProfileChange = { purpose, profile ->
                                container.settings.save(purpose, profile)
                                profiles = profiles + (purpose to profile)
                            },
                            motion = motion,
                            onMotionChange = {
                                container.motion.save(it)
                                motion = it
                            },
                            onSetKey = { push(Route.Key(null, null)) },
                            onClearKey = {
                                container.keyStore.clear()
                                keyChanges++
                            },
                            onOpenUsage = { push(Route.Usage) },
                            onBack = ::pop,
                        )
                    }
                }
                // 打字的時候把導覽收起來，把高度留給正在寫的話
                if (route !is Route.Key && !WindowInsets.isImeVisible) {
                    BottomBar(stack.first()) { stack = listOf(it) }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit) {
    Column(Modifier.fillMaxWidth().background(BgSide)) {
        HorizontalDivider(color = Border)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            for ((tab, label) in TABS) {
                val active = tab == current
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .testTag("tab_$label")
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NavIcon(tab, active)
                    Text(
                        label,
                        Modifier.padding(top = 4.dp),
                        color = if (active) Ink else InkSoft,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 網頁版側邊欄的小圖示：首頁是圓角方塊、森林是小樹、週報是紙頁、記憶是兩顆思緒泡泡。 */
@Composable
private fun NavIcon(tab: Route, active: Boolean) {
    val muted = Color(0xFF8F7C6F)
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 18f
        when (tab) {
            Route.Today -> drawRoundRect(if (active) Accent else Color(0xFF84776F), cornerRadius = CornerRadius(6 * u))
            Route.Forest -> {
                drawRoundRect(if (active) Trunk else muted, Offset(7.5f * u, 10 * u), Size(3 * u, 8 * u), CornerRadius(u))
                drawCircle(if (active) Color(0xFF719D68) else Color(0xFF71886C), 6.5f * u, Offset(9 * u, 6.5f * u))
            }
            Route.Report -> {
                val color = if (active) Accent else muted
                drawRoundRect(color, Offset(1.5f * u, 0.75f * u), Size(15 * u, 16.5f * u), CornerRadius(4 * u), style = Stroke(1.5f * u))
                drawRoundRect(color, Offset(5.5f * u, 7 * u), Size(7 * u, 1.5f * u), CornerRadius(u))
                drawRoundRect(color, Offset(6.5f * u, 10.5f * u), Size(5 * u, 1.5f * u), CornerRadius(u))
            }
            Route.Memories -> {
                drawCircle(if (active) Accent else muted, 6.5f * u, Offset(6.5f * u, 11.5f * u))
                drawCircle(if (active) Color(0xFF719D68) else Color(0xFFB4A093), 4 * u, Offset(14 * u, 4 * u))
            }
            else -> Unit
        }
    }
}
