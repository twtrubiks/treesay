package com.twtrubiks.treesay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 輸入 Claude API key。第一次按種樹（或打開週報）時才出現——白天丟訊息不需要 AI，不該一開門就先要 key；
 * 之後從「設定」換一把也是這個畫面（[replacing]）。[reason] 是為什麼現在需要 key，例如種樹、或剛剛那把打不開門。
 * 按下去先拿這把 key 敲一次門（列模型，不花錢），打得開才存。輸入框與按鈕放在畫面上半部，鍵盤跳出來時也按得到。
 */
@Composable
fun KeySetupScreen(reason: String?, replacing: Boolean, onSaved: (String) -> Unit, onCancel: () -> Unit) {
    // 不用 rememberSaveable：key 不該進到系統保存的 instance state
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val key = ApiKeys.sanitize(input)
        if (key.isEmpty() || checking) return
        checking = true
        failure = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { ApiKeyCheck.check(key) }
            checking = false
            when (result) {
                KeyCheck.Ok -> onSaved(key)
                is KeyCheck.Failed -> failure = result.message
            }
        }
    }

    BackHandler(onBack = onCancel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (replacing) "換一把 API key" else "讓樹連上森林",
            color = Ink,
            fontSize = 30.sp,
            fontFamily = FontFamily.Serif,
        )
        if (reason != null) {
            Text(
                reason,
                color = Ink,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgBubbleTree, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        Text(
            "樹說用你自己的 Claude API key，請 Claude 把你說的話整理成日記、回你一段話。費用算在這把 key 的帳號上。",
            color = InkSoft,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        )
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                failure = null
            },
            label = { Text("Claude API key") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            enabled = !checking,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_input"),
        )
        Button(onClick = ::submit, enabled = input.isNotBlank() && !checking) {
            Text(if (checking) "正在敲門…" else "連上森林", fontSize = 17.sp)
        }
        failure?.let {
            Text(
                it,
                color = Ink,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgBubbleTree, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        Text(
            "key 加密後存在這支手機裡。種樹時，那天說的話和照片會經由 Claude API 送到 Anthropic，整理成日記再送回來。",
            color = InkMute,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Text("還沒有 key 的話，到 platform.claude.com 建立一把。", color = InkMute, fontSize = 15.sp)
        TextButton(onClick = onCancel) { Text(if (replacing) "先不換了" else "先不用了，回去繼續寫", color = InkSoft) }
    }
}
