package com.twtrubiks.treesay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val CardShape = RoundedCornerShape(20.dp)
val UserBubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
val TreeBubbleShape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

/** 每一頁的大標題（明體），網頁版的 `.title`。 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(BgCard, CardShape)
            .border(1.dp, Border, CardShape)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        content = content,
    )
}

/** 安靜的說明卡：空狀態、還在生長中、樹睡著了。 */
@Composable
fun QuietCard(text: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Card {
        Text(
            text,
            Modifier.fillMaxWidth().padding(top = 18.dp),
            color = InkSoft,
            fontSize = 18.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
        if (sub != null) {
            Text(
                sub,
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = InkMute,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** 樹的頭像：一顆暖色的圓。 */
@Composable
fun TreeAvatar(size: Dp = 30.dp) {
    Box(Modifier.padding(top = 2.dp).size(size).background(TreeAvatar, CircleShape))
}

/** 樹的回覆：左邊是樹，右邊是話。 */
@Composable
fun TreeReply(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TreeAvatar()
        Box(
            Modifier
                .background(BgBubbleTree, TreeBubbleShape)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) { content() }
    }
}

/** 使用者說的一句話（可能附一張照片）。 */
@Composable
fun UserBubble(message: MessageView, modifier: Modifier = Modifier, maxPhotoWidth: Dp = 220.dp) {
    Column(
        modifier
            .background(BgBubbleUser, UserBubbleShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        message.photo?.let { PhotoThumb(it, Modifier.widthIn(max = maxPhotoWidth).padding(bottom = if (message.content.isEmpty()) 0.dp else 6.dp)) }
        if (message.content.isNotEmpty()) BodyText(message.content, color = InkStrong)
    }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = Ink, size: Int = 17) {
    Text(text, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.65f).sp)
}

/** 日記裡的照片：讀的時候才縮成小圖、轉正，原圖不動。讀不到就安靜地不顯示。 */
@Composable
fun PhotoThumb(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            context.container.diary.photoFile(path)?.let { PhotoPrep.thumbnail(it, 720)?.asImageBitmap() }
        }
    }
    image?.let {
        Image(
            it,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth,
        )
    }
}

/**
 * 拿到完整文字後在本地做打字機效果。畫面安靜時游標不閃，字照樣一個一個出來：那是樹回話的節奏，不是裝飾。
 */
@Composable
fun TypeWriter(text: String, color: Color = Ink, speedMs: Long = 55, onDone: () -> Unit = {}) {
    // 轉螢幕時接著打，不從頭再打一次
    var shown by rememberSaveable(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        val chars = text.codePointCount(0, text.length)
        while (shown < chars) {
            delay(speedMs)
            shown++
        }
        onDone()
    }
    val reduceMotion = LocalReduceMotion.current
    val blink = if (reduceMotion) {
        1f
    } else {
        // 一秒一閃：前半秒亮、後半秒暗，不漸變
        rememberInfiniteTransition(label = "cursor").animateFloat(
            1f,
            1f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 1000
                    1f at 499
                    0f at 500
                    0f at 999
                },
                RepeatMode.Restart,
            ),
            label = "blink",
        ).value
    }
    val done = shown >= text.codePointCount(0, text.length)
    Text(
        buildAnnotatedString {
            append(text.substring(0, text.offsetByCodePoints(0, shown.coerceAtMost(text.codePointCount(0, text.length)))))
            if (!done) withStyle(SpanStyle(color = color.copy(alpha = blink))) { append(" ▍") }
        },
        color = color,
        fontSize = 17.sp,
        lineHeight = 29.sp,
    )
}

/** 錯誤（療癒文案）：浮在畫面下方，點一下就收起來，幾秒後自己退場。 */
@Composable
fun BoxScope.Toast(message: String?, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(6_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp, start = 20.dp, end = 20.dp),
    ) {
        Text(
            message.orEmpty(),
            modifier = Modifier
                .background(Ink, RoundedCornerShape(20.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            color = BgCard,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 一個安靜的文字按鈕（網頁版的小字連結）。 */
@Composable
fun QuietLink(text: String, modifier: Modifier = Modifier, color: Color = InkMute, size: Int = 15, onClick: () -> Unit) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        color = color,
        fontSize = size.sp,
    )
}

/** 圓角的小膠囊按鈕：[filled] 是主要動作，否則是外框。 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        text,
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .then(if (filled) Modifier.background(Accent, shape) else Modifier.border(1.dp, Accent, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        color = if (filled) AccentContrast else Accent,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HGap(width: Dp) = Spacer(Modifier.width(width))

/** 標題下面的一段說明。 */
@Composable
fun Subtitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.heightIn(min = 0.dp), color = InkMute, fontSize = 15.sp, lineHeight = 25.sp)
}
