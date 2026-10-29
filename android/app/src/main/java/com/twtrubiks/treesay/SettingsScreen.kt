package com.twtrubiks.treesay

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 設定：API key、種樹與週報各自的模型與 effort、AI 費用、畫面動態、匯出匯入。
 * 跟網頁版側邊欄底部的小字一樣是工具，入口是首頁右上角的「設定」，不放進主要動線。
 */
@Composable
fun SettingsScreen(
    apiKey: String?,
    profiles: Map<Purpose, AiProfile>,
    onProfileChange: (Purpose, AiProfile) -> Unit,
    motion: MotionPref,
    onMotionChange: (MotionPref) -> Unit,
    onSetKey: () -> Unit,
    onClearKey: () -> Unit,
    onOpenUsage: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val diary = context.container.diary
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                backupMessage = try {
                    withContext(Dispatchers.IO) { write(context, uri, diary.export()) }
                    "匯出好了。照片不在檔案裡，只記了檔名。"
                } catch (e: IOException) {
                    "存不進去這個位置，換一個地方試試 🌙"
                }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                backupMessage = try {
                    val result = withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: throw IOException("讀不到檔案")
                        diary.import(text)
                    }
                    importSummary(result)
                } catch (e: TreeSayException) {
                    e.message
                } catch (e: IOException) {
                    Diary.IMPORT_UNREADABLE
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        QuietLink("← 回去", color = InkSoft, size = 16, onClick = onBack)
        PageTitle("設定")

        Section("Claude API key") {
            if (apiKey == null) {
                Subtitle("還沒有 key。白天丟訊息不需要它；種樹和週報要請 Claude 幫忙時才用得到，費用算在這把 key 的帳號上。")
                Pill("連上森林", onClick = onSetKey)
            } else {
                Text("目前這把：${ApiKeys.masked(apiKey)}", color = Ink, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onSetKey) { Text("換一把") }
                    TextButton(onClick = { confirmingClear = true }) { Text("清除這把 key", color = InkSoft) }
                }
            }
        }

        for (purpose in Purpose.entries) {
            val profile = profiles.getValue(purpose)
            Section(purpose.label) {
                Text(hintFor(purpose), color = InkMute, fontSize = 15.sp, lineHeight = 22.sp)
                Choices(
                    title = "模型",
                    options = AiProfiles.MODELS.map { it.id to it.label },
                    selected = profile.model,
                    default = AiProfiles.default(purpose).model,
                    tagPrefix = "${purpose.key}_model",
                    onSelect = { onProfileChange(purpose, profile.copy(model = it)) },
                )
                Choices(
                    title = "effort",
                    options = AiProfiles.EFFORTS.map { it to it },
                    selected = profile.effort,
                    default = AiProfiles.default(purpose).effort,
                    tagPrefix = "${purpose.key}_effort",
                    onSelect = { onProfileChange(purpose, profile.copy(effort = it)) },
                )
            }
        }
        Column {
            Text("每一次呼叫都按 token 計費，算在這把 key 的帳號上。", color = InkMute, fontSize = 15.sp)
            QuietLink("看看每個月花了多少 →", color = Accent, size = 15, onClick = onOpenUsage)
        }

        Section("畫面動態") {
            Text("樹的風、呼吸和偶爾來訪的小東西。關掉之後畫面就安靜下來。", color = InkMute, fontSize = 15.sp, lineHeight = 22.sp)
            Choices(
                title = null,
                options = MotionPref.entries.map { it.key to it.label },
                selected = motion.key,
                default = MotionPref.ON.key,
                tagPrefix = "motion",
                onSelect = { key -> onMotionChange(MotionPref.entries.first { it.key == key }) },
            )
        }

        Section("匯出與匯入") {
            Text(
                "日記只存在這支手機裡。換手機、或移除 App 之前（移除會連同日記一起刪掉），先匯出一份。" +
                    "匯出的檔案電腦版的樹說也讀得懂；匯入只加不蓋，手機上已經有的日子不會被改掉。照片不在檔案裡。",
                color = InkMute,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    exporter.launch("treesay-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json")
                }) { Text("匯出日記") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                    Text("匯入")
                }
            }
            backupMessage?.let { Text(it, color = Ink, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.testTag("backup_message")) }
        }

        // 不寫「只有你和樹知道」——內容仍會送到 Anthropic 生成，只能說沒有別人看得到
        Text("沒有別人看得到。\n不用登入，說完就好。", color = InkMute, fontSize = 15.sp, lineHeight = 24.sp)
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清除這把 key？") },
            text = { Text("清除之後還是可以照常寫，種樹時會再請你給一把 key。日記不會受影響。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClearKey()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("留著", color = InkSoft) }
            },
        )
    }
}

private fun write(context: android.content.Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("打不開 $uri")
}

private fun importSummary(r: ImportResult): String = buildString {
    append("加進了 ${r.daysAdded} 天、${r.reportsAdded} 份週報、${r.memoriesAdded} 條記憶。")
    val skipped = r.daysSkipped + r.reportsSkipped + r.memoriesSkipped
    if (skipped > 0) append("手機上已經有的 $skipped 筆留著原樣。")
    if (r.photosMissing > 0) append("有 ${r.photosMissing} 張照片不在這支手機裡，那幾則話照樣留著。")
}

private fun hintFor(purpose: Purpose): String = when (purpose) {
    Purpose.PLANT -> "日記是一百多字的溫暖短文，不是推理題。effort 開高只是多花錢，不會讓文字更暖。"
    Purpose.WEEKLY -> "把一週寫好的日記濃縮成幾個欄位，模型小一階就夠。一週只跑一次。"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Ink, fontSize = 20.sp, fontFamily = FontFamily.Serif)
        content()
    }
}

@Composable
private fun Choices(
    title: String?,
    options: List<Pair<String, String>>,
    selected: String,
    default: String,
    tagPrefix: String,
    onSelect: (String) -> Unit,
) {
    Column {
        if (title != null) Text(title, color = InkSoft, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in options) {
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(if (value == default && title != null) "$label（預設）" else label) },
                    modifier = Modifier.testTag("${tagPrefix}_$value"),
                )
            }
        }
    }
}
