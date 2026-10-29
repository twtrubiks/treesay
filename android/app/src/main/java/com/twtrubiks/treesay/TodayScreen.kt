package com.twtrubiks.treesay

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private enum class Overlay { NONE, GROWING, DONE }

/** 種樹要請 Claude 幫忙，還沒有 key 的時候，請使用者給一把時說的話。 */
const val PLANT_NEEDS_KEY = "種樹要請 Claude 幫忙，把今天說的話整理成日記、回你一段話。先給樹一把 Claude API key。"

/**
 * 首頁：白天把碎片隨手丟給樹，晚上種下今天的樹。跟網頁版 `TodayView.vue` 同一套節奏：
 * 白天只收集、不回應；種樹按鈕沒話時沉睡、有話後等著、入夜亮起——是邀請，不是提醒。
 * 沒有 key 也能寫，按下種樹時才請使用者給 key（[needKeyToPlant]）；給完回到這裡，
 * [pendingPlant] 是剛剛要種的那天，接著種下去。
 */
@Composable
fun TodayScreen(
    onOpenDay: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    pendingPlant: LocalDate?,
    onPendingPlantTaken: () -> Unit,
    needKeyToPlant: (reason: String, date: LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val diary = container.diary
    val scope = rememberCoroutineScope()
    val sky = LocalSky.current
    val reduceMotion = LocalReduceMotion.current

    var today by remember { mutableStateOf<TodayView?>(null) }
    // 轉螢幕時不能丟：樹正在回話的時候轉了一下，回話就不見了
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    // 按下「聊聊這個」後，把問題留在輸入框上方——按鈕說了「這個」，寫的時候就得看得到是什麼
    var answering by rememberSaveable { mutableStateOf<String?>(null) }
    var questionHidden by rememberSaveable { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var nudge by remember { mutableStateOf(0) }
    val composerFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    suspend fun reload() {
        today = withContext(Dispatchers.IO) { diary.today() }
    }

    suspend fun follow(date: LocalDate, workId: java.util.UUID) {
        val failure = PlantWorker.await(context, workId)
        reload()
        if (failure == null) {
            overlay = Overlay.DONE
        } else {
            overlay = Overlay.NONE
            if (failure.kind == TreeSayException.Kind.KEY_MISSING || failure.kind == TreeSayException.Kind.KEY_INVALID) {
                needKeyToPlant(failure.message.orEmpty(), date)
            } else {
                error = failure.message
            }
        }
    }

    fun plant() {
        val date = today?.day?.date ?: return
        if (container.keyStore.get() == null) {
            needKeyToPlant(PLANT_NEEDS_KEY, date)
            return
        }
        overlay = Overlay.GROWING
        scope.launch { follow(date, PlantWorker.start(context, date)) }
    }

    // 剛給完 key 回來：接著種剛剛要種的那天
    LaunchedEffect(pendingPlant, today?.day?.date) {
        if (pendingPlant != null && pendingPlant == today?.day?.date) {
            onPendingPlantTaken()
            plant()
        }
    }

    // 新的一句話進來，捲到最下面讓人看到它
    LaunchedEffect(today?.day?.messages?.size) {
        delay(80)
        scroll.animateScrollTo(scroll.maxValue)
    }

    LaunchedEffect(Unit) {
        try {
            reload()
        } catch (e: Exception) {
            error = Diary.TREE_ASLEEP
        }
        // 上次種到一半就離開了：樹還在長的話接著等它，沒在長的話留著「再種一次」
        val day = today?.day
        if (day?.status == DayStatus.PLANTING) {
            overlay = Overlay.GROWING
            PlantWorker.running(context, day.date)?.let { follow(day.date, it) }
        } else if (overlay == Overlay.GROWING) {
            // 等樹長的時候轉了螢幕，轉完樹已經長好（或沒長成）：長好了接著給人看樹的回話
            overlay = if (day?.status == DayStatus.PLANTED) Overlay.DONE else Overlay.NONE
        }
        // 每分鐘對一次日期——開著跨過凌晨 4:00 的話，今天還停在前一天，不重抓就寫不了今天
        while (true) {
            delay(60_000)
            val now = TreeDay.effectiveDate(LocalDateTime.now())
            if (overlay == Overlay.NONE && today?.day?.date != now) {
                runCatching { reload() }
                // 新的一天有新的問題：昨天按過「先不想」、答到一半的引用都不該跟過來
                questionHidden = false
                answering = null
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri.toString()
    }

    fun send() {
        val text = input.trim()
        val uri = photo
        if ((text.isEmpty() && uri == null) || sending) return
        sending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    diary.send(text, uri?.let { readPhoto(context, it.toUri()) })
                }
                input = ""
                photo = null
                answering = null
                reload()
            } catch (e: TreeSayException) {
                error = e.message
            } catch (e: IOException) {
                error = "這張照片讀不到，換一張試試 🌙"
            } finally {
                sending = false
            }
        }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                reload()
            } catch (e: TreeSayException) {
                error = e.message
            }
        }
    }

    val view = today
    Box(Modifier.fillMaxSize()) {
        if (view != null) {
            val day = view.day
            val collecting = day.status == DayStatus.COLLECTING
            val planted = day.status == DayStatus.PLANTED
            val hasMessages = day.messages.isNotEmpty()
            val evening = sky.night

            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.dateLabel(day.date), color = InkMute, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        // 設定是工具，不是這個 App 想讓人每天看的東西：放小字
                        QuietLink("設定", onClick = onOpenSettings)
                    }
                    Text(
                        if (planted) "今天的樹已經種下了" else Format.greeting(LocalDateTime.now().toLocalTime()),
                        color = Ink,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Serif,
                    )
                    if (!planted) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // 樹隨訊息數慢慢長大（中性樹，種樹後才有情緒）。起點 48dp：再小，風和來訪者就看不見了
                            val treeWidth by animateDpAsState(
                                (48f + day.messages.size * 5.25f).coerceAtMost(90f).dp,
                                tween(if (reduceMotion) 0 else 800),
                                label = "treeWidth",
                            )
                            EmotionTree(width = treeWidth, breathe = hasMessages, idle = true, seasonal = true)
                            HGap(16.dp)
                            if (collecting) PlantButton(hasMessages, lit = evening && hasMessages, onClick = ::plant)
                        }
                    }
                    Gap(18.dp)

                    if (collecting && !questionHidden) {
                        QuestionCard(
                            day.question,
                            onTalk = {
                                answering = day.question
                                composerFocus.requestFocus()
                                nudge++
                            },
                            onHide = { questionHidden = true },
                        )
                        Gap(18.dp)
                    }

                    // 前一兩天留了話沒收尾：遞一個安靜的邀請，不是待辦。今天種完就不再出現
                    val candidate = view.backfillCandidate
                    if (collecting && candidate != null) {
                        val word = if (ChronoUnit.DAYS.between(candidate, day.date) == 1L) "昨天" else "前天"
                        QuietLink("${word}說的話還放著，想替它種棵樹嗎 🌱", size = 16) { onOpenDay(candidate) }
                        Gap(14.dp)
                    }

                    if (planted) {
                        PlantedDiary(day)
                        Gap(18.dp)
                        Text("新的想法，留到明天的樹。明天見 🌙", color = InkMute, fontSize = 15.sp)
                    } else {
                        for (m in day.messages) {
                            MessageRow(
                                message = m,
                                editable = collecting,
                                editing = editingId == m.id,
                                editText = editText,
                                onEditText = { editText = it },
                                onStartEdit = {
                                    editingId = m.id
                                    editText = m.content
                                },
                                onSaveEdit = {
                                    val text = editText.trim()
                                    editingId = null
                                    // 沒改，或整句被清空（想整句拿掉請用收回）：安靜退出，不打擾也不報錯
                                    if (text != m.content && (text.isNotEmpty() || m.photo != null)) {
                                        act { withContext(Dispatchers.IO) { diary.edit(m.id, text) } }
                                    }
                                },
                                onCancelEdit = { editingId = null },
                                onRetract = { act { withContext(Dispatchers.IO) { diary.retract(m.id) } } },
                            )
                        }
                    }
                }

                if (collecting) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                        Composer(
                            input = input,
                            onInput = { input = it },
                            answering = answering,
                            onDropAnswering = { answering = null },
                            photo = photo,
                            onPickPhoto = {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onClearPhoto = { photo = null },
                            onSend = ::send,
                            focus = composerFocus,
                            nudge = nudge,
                        )
                        // 入夜後把動線縫起來：寫字的地方在下面，收工的按鈕在上面
                        Text(
                            if (evening && hasMessages) {
                                "說得差不多了的話，上面可以種下今天的樹 🌱"
                            } else {
                                "沒有字數要求，寫一句話也可以，什麼都不寫也沒關係。"
                            },
                            Modifier.padding(start = 6.dp, top = 8.dp),
                            color = InkMute,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            if (overlay != Overlay.NONE) {
                PlantOverlay(
                    day = day,
                    done = overlay == Overlay.DONE,
                    onRetry = ::plant,
                    onClose = { overlay = Overlay.NONE },
                )
            }
        }
        Toast(error) { error = null }
    }
}

/** 三個狀態陪一天走：沒訊息時沉睡、有訊息後等著、入夜亮起。 */
@Composable
private fun PlantButton(hasMessages: Boolean, lit: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val background by animateColorAsState(if (lit) Accent else Color.Transparent, tween(300), label = "lit")
    Text(
        "種下今天的樹 🌱",
        Modifier
            .alpha(if (hasMessages) 1f else 0.35f)
            .clip(shape)
            .background(background, shape)
            .border(1.dp, Accent, shape)
            .clickable(enabled = hasMessages, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        color = if (lit) AccentContrast else Accent,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun QuestionCard(question: String, onTalk: () -> Unit, onHide: () -> Unit) {
    Card {
        BodyText(question, size = 17)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 「說話」跟「算了」不該長得一樣重——一個很累的人要能一眼看到說話在哪
            Pill("聊聊這個", onClick = onTalk)
            HGap(8.dp)
            QuietLink("先不想", size = 16, onClick = onHide)
        }
    }
}

@Composable
private fun PlantedDiary(day: DayView) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 種下後樹還是活的：呼吸、隨風擺。不開季節——情緒色不讓季節蓋，也就不讓它掉自己的葉子
            EmotionTree(emotion = day.emotion, width = 30.dp, breathe = true, idle = true)
            HGap(10.dp)
            day.emotion?.let { Text(it.label, color = InkSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
        Gap(12.dp)
        Text(day.diary.orEmpty(), color = Ink, fontSize = 17.sp, lineHeight = 32.sp)
    }
    Gap(18.dp)
    TreeReply { BodyText(day.treeReply.orEmpty()) }
}

@Composable
private fun MessageRow(
    message: MessageView,
    editable: Boolean,
    editing: Boolean,
    editText: String,
    onEditText: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRetract: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editable && !editing) {
                // 改字與收回：平時淡淡的、不搶話，但看得見——完全隱形的話，打錯字的人只會以為說出去就改不了了
                QuietLink("✎", color = InkFaint, size = 16, onClick = onStartEdit)
                QuietLink("✕", color = InkFaint, size = 15, onClick = onRetract)
                HGap(2.dp)
            }
            if (editing) {
                // 改字：泡泡原地變輸入框，不彈視窗——一句打錯的話不值得一個對話框
                val focus = remember { FocusRequester() }
                Column(
                    Modifier
                        .widthIn(max = 300.dp)
                        .background(BgBubbleUser, UserBubbleShape)
                        .border(2.dp, Accent, UserBubbleShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    message.photo?.let { PhotoThumb(it, Modifier.widthIn(max = 200.dp).padding(bottom = 6.dp)) }
                    BasicTextField(
                        editText,
                        onEditText,
                        Modifier.focusRequester(focus).testTag("edit_input"),
                        textStyle = TextStyle(color = InkStrong, fontSize = 17.sp, lineHeight = 28.sp),
                        cursorBrush = SolidColor(Accent),
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        QuietLink("改好", color = AccentDeep, size = 14, onClick = onSaveEdit)
                        QuietLink("不改了", size = 14, onClick = onCancelEdit)
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                UserBubble(message, Modifier.widthIn(max = 280.dp))
            }
        }
        Text(
            "${Format.time(message.createdAt)} · 樹已經收下",
            Modifier.padding(top = 3.dp, end = 4.dp),
            color = InkMute,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    answering: String?,
    onDropAnswering: () -> Unit,
    photo: String?,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onSend: () -> Unit,
    focus: FocusRequester,
    nudge: Int,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 「聊聊這個」按下後的微光，1.6 秒後自己退掉
    var glowing by remember { mutableStateOf(false) }
    LaunchedEffect(nudge) {
        if (nudge > 0) {
            glowing = true
            delay(1600)
            glowing = false
        }
    }
    val shape = RoundedCornerShape(22.dp)
    // 游標在寫字的地方，框就跟著醒過來——很淡，是「我在聽」不是「請填寫」
    val border by animateColorAsState(
        when {
            glowing -> Accent
            focused -> ComposerFocus
            else -> Border
        },
        tween(500),
        label = "composerBorder",
    )
    val glow by animateFloatAsState(if (glowing) 1f else 0f, tween(500), label = "glow")
    Column(
        Modifier
            .fillMaxWidth()
            .shadow((6 * glow).dp, shape, ambientColor = Accent, spotColor = Accent)
            .background(BgCard, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (answering != null) {
            // 正在回應的問題：淡引用，不是待填欄位——沒有底色、沒有邊框、隨手可拿掉
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).size(2.dp, 34.dp).background(BorderSoft))
                HGap(8.dp)
                Text("正在回應", color = InkFaint, fontSize = 13.sp)
                HGap(6.dp)
                Text(
                    answering,
                    Modifier.weight(1f),
                    color = InkSoft,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontFamily = FontFamily.Serif,
                )
                QuietLink("✕", color = InkFaint, size = 13, onClick = onDropAnswering)
            }
        }
        if (photo != null) {
            Box(Modifier.padding(bottom = 10.dp)) {
                PickedPhoto(photo)
                Text(
                    "✕",
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(InkSoft, CircleShape)
                        .clickable(onClick = onClearPhoto)
                        .padding(top = 1.dp),
                    color = BgCard,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Box {
            if (input.isEmpty()) Text("想說什麼都可以，樹在聽……", color = InkMute, fontSize = 17.sp)
            // 輸入框跟著字長高，上限約 8 行：再高會把上面的訊息擠掉，佔滿螢幕的輸入框本身就是在催人寫滿它
            BasicTextField(
                input,
                onInput,
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp, max = 220.dp)
                    .focusRequester(focus)
                    .testTag("composer_input"),
                textStyle = TextStyle(color = Ink, fontSize = 17.sp, lineHeight = 27.sp),
                cursorBrush = SolidColor(Accent),
                interactionSource = interaction,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "📷",
                Modifier
                    .alpha(0.7f)
                    .clip(CircleShape)
                    .clickable(onClick = onPickPhoto)
                    .padding(8.dp)
                    .testTag("photo_button"),
                fontSize = 21.sp,
            )
            HGap(8.dp)
            Pill("丟給樹", onClick = onSend)
        }
    }
}

/** 還沒送出的照片預覽。 */
@Composable
private fun PickedPhoto(uri: String) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
                    ?.let { PhotoPrep.thumbnail(it, 400)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    image?.let {
        Image(it, null, Modifier.heightIn(max = 96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
    }
}

/** 把挑好的照片讀成要存的檔案。原圖照存，縮圖只在送給樹看時才做。 */
private fun readPhoto(context: Context, uri: Uri): NewPhoto {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("讀不到 $uri")
    val extension = when (context.contentResolver.getType(uri)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        else -> ".jpg"
    }
    return NewPhoto(bytes, extension)
}

/**
 * 種樹的全螢幕畫面：最後一句話陪著等待；日記出來後淡出——話被樹收進日記裡了。
 * 長的時候不能關（樹會在背景種完），長好了才給「今天先到這裡」。
 */
@Composable
private fun PlantOverlay(day: DayView, done: Boolean, onRetry: () -> Unit, onClose: () -> Unit) {
    BackHandler { if (done) onClose() }
    var replyTyped by rememberSaveable(done) { mutableStateOf(false) }
    val last = day.messages.lastOrNull()
    val lastAlpha by animateFloatAsState(if (done) 0f else 1f, tween(800), label = "absorbed")
    val footerAlpha by animateFloatAsState(if (!done || replyTyped) 1f else 0f, tween(800), label = "footer")
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF80512F), Color(0xFF421C19))))
            // 吃掉點擊，不讓底下的首頁被點到
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (last != null && last.content.isNotEmpty()) {
                Text(
                    last.content,
                    Modifier
                        .align(Alignment.End)
                        .alpha(lastAlpha)
                        .widthIn(max = 300.dp)
                        .background(Color(0xE6F3EADD), UserBubbleShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    color = Ink,
                    fontSize = 17.sp,
                    lineHeight = 27.sp,
                )
            }
            Gap(24.dp)
            GrowingTree()
            if (!done) {
                Gap(24.dp)
                Text("樹正在慢慢長大……", color = Color(0xFFF7F1E7), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
                Gap(8.dp)
                Text("深呼吸一下，等待也是今天的一部分。", color = Color(0xB3E5DDD0), fontSize = 16.sp)
            } else {
                Gap(24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xF5FCF8F1), RoundedCornerShape(18.dp))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmotionTree(emotion = day.emotion, width = 26.dp)
                        HGap(8.dp)
                        day.emotion?.let { Text(it.label, color = InkSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    }
                    Gap(8.dp)
                    Text(day.diary.orEmpty(), color = Ink, fontSize = 17.sp, lineHeight = 30.sp)
                }
                Gap(20.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TreeAvatar()
                    Box(
                        Modifier
                            .background(Color(0xF5FCF8F1), TreeBubbleShape)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) { TypeWriter(day.treeReply.orEmpty(), onDone = { replyTyped = true }) }
                }
            }
            Gap(36.dp)
            if (done) {
                Text(
                    "這棵樹種好了，今天先到這裡 🌙",
                    Modifier
                        .alpha(footerAlpha)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x26FBF4EA))
                        .border(1.dp, Color(0x59FBF4EA), RoundedCornerShape(20.dp))
                        .clickable(enabled = replyTyped, onClick = onClose)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    color = Color(0xFFFBF4EA),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                QuietLink("等太久了嗎？再種一次", color = Color(0x99E5DDD0), size = 15, onClick = onRetry)
            }
        }
    }
}
