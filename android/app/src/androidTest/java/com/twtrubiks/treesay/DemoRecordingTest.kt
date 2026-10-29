package com.twtrubiks.treesay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 *
 * CI 沒有真的 API key：
 * - 第一次按種樹時輸入一把假 key，讓真的 Claude API 回 401——同時驗證 SDK 在 Android 上連得到 Anthropic、解讀得了錯誤
 * - 之後的種樹與週報打到模擬器上的假 Claude 伺服器（[MockWebServer]），回應照 Messages API 的格式。
 *   請求一樣經過真的 SDK 序列化（照片的 image block、structured outputs 的 schema），回應一樣由 SDK 解析
 * - 過去一個月的日記用「匯入」先放進去，森林、週報、記憶才有東西看
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    private val mapper = ObjectMapper()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<JsonNode>()

    private val today: LocalDate = TreeDay.effectiveDate(LocalDateTime.now())

    @After
    fun cleanUp() {
        container.claudeBaseUrlForTests = null
        container.keyStore.clear()
        server.shutdown()
    }

    @Test
    fun demo() {
        container.keyStore.clear()
        Purpose.entries.forEach { container.settings.save(it, AiProfiles.default(it)) }
        container.motion.save(MotionPref.ON)
        server.dispatcher = FakeClaude()
        server.start()
        container.claudeBaseUrlForTests = server.url("/").toString()
        seedLastMonth()
        // 今天一早拍的天空
        container.diary.send("", NewPhoto(sky(), ".jpg"))

        launchApp()
        // 沒有 key 也直接進首頁：白天丟訊息不需要 AI
        waitFor(By.text("種下今天的樹 🌱"), timeout = 30_000)
        pause(2500)
        screenshot("01-today")

        backfillYesterday()
        talkToTheTree()
        plantToday()
        walkTheForest()
        readTheWeek()
        letGoOfAMemory()
        settings()

        // 種樹的請求真的帶了照片，週報的請求帶了這週的日記與記憶
        val plant = requests.first { it.toString().contains("修好了") }
        assertTrue(plant["messages"][0]["content"].any { it["type"].asText() == "image" })
        assertTrue(requests.any { it["output_config"]["format"]["schema"]["properties"].has("good_things") })
        assertEquals(null, container.keyStore.get())
    }

    /** 首頁遞的補種邀請 → 詳情頁 → 第一次種樹要 key：假 key 被真的 Claude API 擋下 → 存一把給假伺服器用的 key。 */
    private fun backfillYesterday() {
        find(By.textContains("說的話還放著")).click()
        waitFor(By.text("種下這一天的樹"))
        pause()
        find(By.text("種下這一天的樹")).click()

        waitFor(By.text("讓樹連上森林"))
        pause(1500)
        screenshot("02-key-needed")
        find(By.res("api_key_input")).apply {
            click()
            text = "sk-ant-demo-not-a-real-key"
        }
        pause(800)
        find(By.text("連上森林")).click()
        // 真的打到 Claude API，回 401
        waitFor(By.textContains("打不開門"), timeout = 30_000)
        pause(1500)
        screenshot("03-key-rejected")

        // 之後的呼叫打到假伺服器，這把 key 不會送到 Anthropic
        container.keyStore.save("sk-ant-demo-only-1234")
        hideKeyboard()
        find(By.text("先不用了，回去繼續寫")).click()
        find(By.text("種下這一天的樹")).click()
        waitFor(By.textContains("昨天的我"), timeout = 30_000)
        pause(1500)
        screenshot("04-backfilled")
        find(By.text("看看那天的隻字片語 ﹀")).click()
        pause(1500)
        find(By.text("← 回森林")).click()
    }

    /** 聊聊今天的問題、丟幾句話、改一句、收回一句。 */
    private fun talkToTheTree() {
        find(By.text("聊聊這個")).click()
        pause()
        send("今天早上去河堤跑了一圈，風有點涼")
        send("晚上終於把拖了很久的 bug 修好了")
        send("這句說錯了")
        hideKeyboard()
        pause()
        // 改字：泡泡原地變輸入框
        beside("✎", "今天早上去河堤跑了一圈，風有點涼").click()
        find(By.res("edit_input")).text = "今天早上去河堤跑了一圈，風有點涼，但很舒服"
        pause()
        hideKeyboard()
        find(By.text("改好")).click()
        waitFor(By.text("今天早上去河堤跑了一圈，風有點涼，但很舒服"))
        pause()
        // 收回說錯的那句
        beside("✕", "這句說錯了").click()
        waitGone(By.text("這句說錯了"))
        pause(1500)
        screenshot("05-messages")
    }

    /** 跟某一句話同一列的那顆小按鈕（✎ 或 ✕）。 */
    private fun beside(button: String, message: String): UiObject2 {
        val anchor = find(By.text(message)).visibleCenter.y
        return device.findObjects(By.text(button)).minBy { kotlin.math.abs(it.visibleCenter.y - anchor) }
    }

    private fun send(text: String) {
        find(By.res("composer_input")).text = text
        pause(700)
        find(By.text("丟給樹")).click()
        // 送出成功會清空輸入框；清空之前就打下一句的話，會被這一下蓋掉
        waitUntil { device.findObject(By.res("composer_input"))?.text.isNullOrEmpty() }
        pause(900)
    }

    /** 種下今天的樹：全螢幕的等待、日記長出來、樹一個字一個字地回話。 */
    private fun plantToday() {
        find(By.scrollable(true)).scroll(Direction.UP, 1f)
        pause()
        find(By.text("種下今天的樹 🌱")).click()
        waitFor(By.text("樹正在慢慢長大……"))
        pause(2000)
        screenshot("06-growing")
        waitFor(By.text("這棵樹種好了，今天先到這裡 🌙"), timeout = 40_000)
        // 樹回話回到一半轉個螢幕：回話留著、接著打，不會不見也不會從頭再打
        pause(1500)
        device.setOrientationLeft()
        // 橫的時候畫面矮，回話在下面：捲到看得見（UiAutomator 找不到畫面外的元件）
        reveal(By.textContains("河堤的風"))
        pause(2000)
        screenshot("06b-rotated")
        device.setOrientationNatural()
        device.unfreezeRotation()
        reveal(By.textContains("河堤的風"))
        // 等打字機把樹的回覆打完，按鈕才按得下去。打字時畫面一直在變，按之前重新找一次
        SystemClock.sleep(3_000)
        screenshot("07-planted")
        reveal(By.text("這棵樹種好了，今天先到這裡 🌙")).click()
        waitFor(By.text("新的想法，留到明天的樹。明天見 🌙"))
        pause(1500)
        screenshot("08-today-planted")
    }

    private fun walkTheForest() {
        find(By.res("tab_森林")).click()
        waitFor(By.textContains("月的森林"))
        pause(2000)
        screenshot("09-forest")
        find(By.res("day_$today")).click()
        waitFor(By.text("看看那天的隻字片語 ﹀"))
        pause(1500)
        find(By.text("← 回森林")).click()
        pause()
    }

    /** 上一週還沒整理過：小鳥飛一下，假伺服器回週報。 */
    private fun readTheWeek() {
        find(By.res("tab_週報")).click()
        waitFor(By.text("這週的好事"), timeout = 30_000)
        pause(2000)
        screenshot("10-report")
        find(By.scrollable(true)).scroll(Direction.DOWN, 1f)
        pause(2000)
    }

    /** 兩段式放下：第一按只是問一聲。 */
    private fun letGoOfAMemory() {
        find(By.res("tab_記憶")).click()
        waitFor(By.text("養了一隻貓，叫麻糬"))
        pause(1500)
        find(By.res("let_go")).click()
        waitFor(By.text("再按一次，就放下"))
        pause()
        screenshot("11-memories")
        find(By.text("再按一次，就放下")).click()
        pause(1500)
    }

    private fun settings() {
        find(By.res("tab_首頁")).click()
        find(By.text("設定")).click()
        waitFor(By.text("目前這把：••••1234"))
        pause(1500)
        screenshot("12-settings")
        find(By.res("plant_effort_low")).click()
        pause()
        reveal(By.text("看看每個月花了多少 →")).click()
        waitFor(By.textContains("一共約"))
        pause(2000)
        screenshot("13-usage")
        device.pressBack()
        assertEquals(AiProfile("claude-opus-5-5", "low"), container.settings.profile(Purpose.PLANT))

        reveal(By.text("匯出日記"))
        pause()
        screenshot("14-settings-more")
        reveal(By.text("清除這把 key"), Direction.UP).click()
        waitFor(By.text("清除這把 key？"))
        pause()
        find(By.text("清除")).click()
        waitFor(By.text("連上森林"))
        pause(1500)
    }

    // ---------- 假的 Claude ----------

    private inner class FakeClaude : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path?.startsWith("/v1/messages") != true) return MockResponse().setResponseCode(404)
            val body = mapper.readTree(request.body.readUtf8())
            requests.add(body)
            val prompt = body["messages"][0]["content"].last()["text"].asText()
            val weekly = body["output_config"]["format"]["schema"]["properties"].has("good_things")
            val (output, delay) = when {
                weekly -> WEEKLY to 2L
                prompt.contains("使用者昨天像傳訊息一樣") -> BACKFILL to 2L
                else -> TODAY to 4L
            }
            return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(delay, TimeUnit.SECONDS)
                .setBody(
                    mapper.createObjectNode().apply {
                        put("id", "msg_demo")
                        put("type", "message")
                        put("role", "assistant")
                        put("model", body["model"].asText())
                        // 跟真的模型一樣，text 前面先有一段 thinking（effort 開著時會有）
                        putArray("content").apply {
                            addObject().put("type", "thinking").put("thinking", "").put("signature", "EqQBCkYIBxgC")
                            addObject().put("type", "text").put("text", output)
                        }
                        put("stop_reason", "end_turn")
                        putNull("stop_sequence")
                        putObject("usage").put("input_tokens", 2400).put("output_tokens", 900)
                    }.toString(),
                )
        }
    }

    // ---------- 先放進去的一個月 ----------

    private fun seedLastMonth() {
        val root = mapper.createObjectNode()
        val days = root.putArray("days")
        for ((offset, emotion, diary) in PAST) {
            val date = today.minusDays(offset)
            days.addObject().apply {
                put("date", date.toString())
                put("status", "planted")
                put("diary", diary)
                put("emotion", emotion)
                put("tree_reply", "謝謝你把這一天說給我聽，我都收著了。")
                putArray("messages").addObject()
                    .put("content", diary.substringBefore("。"))
                    .put("created_at", "${date}T21:30:00+08:00")
            }
        }
        // 昨天說了話，忘了種
        days.addObject().apply {
            val yesterday = today.minusDays(1)
            put("date", yesterday.toString())
            putArray("messages").apply {
                addObject().put("content", "下午去看了牙醫，有點緊張").put("created_at", "${yesterday}T15:10:00+08:00")
                addObject().put("content", "回家路上買了一束花").put("created_at", "${yesterday}T19:40:00+08:00")
            }
        }
        val memories = root.putArray("memories")
        for ((i, text) in listOf("最近在準備搬家", "養了一隻貓，叫麻糬", "週末喜歡去河堤跑步").withIndex()) {
            memories.addObject().put("content", text).put("source_date", today.minusDays(20L - i * 5).toString())
        }
        container.diary.import(root.toString())
    }

    /** 一張天空的照片：讓種樹的請求真的帶一個 image block。 */
    private fun sky(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 900f, 0xFF8CB2D9.toInt(), 0xFFFBF0E0.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 1200f, 900f, paint)
        paint.shader = null
        paint.color = 0xFFF6D389.toInt()
        canvas.drawCircle(900f, 260f, 110f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private companion object {
        val PAST = listOf(
            Triple(3L, "happy", "今天把陽台的盆栽換了新土。看著新芽冒出來，心情跟著亮了一點。"),
            Triple(4L, "tired", "連開了三個會，腦袋有點空。晚上只想安靜地吃一碗麵。"),
            Triple(6L, "calm", "早上沒有鬧鐘，自然醒。泡了一壺茶，慢慢把書看完。"),
            Triple(8L, "excited", "收到朋友從京都寄來的明信片。下個月要一起去爬山。"),
            Triple(9L, "anxious", "明天要簡報，一直在改投影片。希望明天順利。"),
            Triple(11L, "happy", "麻糬第一次自己走進外出籠。帶牠去打了疫苗，很乖。"),
            Triple(12L, "sad", "外婆住院了，打電話給她，聲音聽起來很累。"),
            Triple(14L, "calm", "下雨天，在咖啡店坐了一下午，把房間的東西列了清單。"),
            Triple(16L, "angry", "房東突然說要漲房租，氣到有點睡不著。"),
            Triple(18L, "tired", "打包了一整天，紙箱堆到天花板。"),
            Triple(21L, "excited", "看到一間喜歡的房子，有陽台、採光很好。"),
            Triple(25L, "calm", "傍晚去河堤跑步，夕陽很漂亮。"),
        )

        val TODAY = """{"diary":"早上去河堤跑了一圈，風有點涼，但很舒服。中午抬頭拍了一張天空，雲很好看。晚上終於把拖了很久的 bug 修好了，心裡輕了一點。","emotion":"calm","tree_reply":"河堤的風、午後的雲、終於修好的 bug——今天收集了好多小小的平靜。拖了很久的事放下了，今晚好好休息，這一天我替你收著。","memory":["最近在修一個拖了很久的 bug"],"keywords":["河堤","天空","bug"]}"""

        val BACKFILL = """{"diary":"下午去看了牙醫，坐在候診室的時候其實有點緊張。看完之後，在回家路上買了一束花，想讓房間亮一點。","emotion":"anxious","tree_reply":"隔了一天才來種下這棵樹也沒關係，昨天的我是緊張著去看牙醫、又記得替自己買一束花的人。那束花現在應該還在房間裡陪著你吧。","memory":[],"keywords":["牙醫","花"]}"""

        val WEEKLY = """{"good_things":["盆栽冒出新芽","收到京都寄來的明信片","麻糬第一次自己走進外出籠"],"bad_things":["連續開會，腦袋有點空","擔心外婆的身體"],"keywords":["盆栽","明信片","麻糬","搬家"],"advice":"下週也照自己的步調來，累了就先泡一壺茶。","memories":["最近在準備搬家","養了一隻貓，叫麻糬","週末喜歡去河堤跑步","最近在修一個拖了很久的 bug"]}"""
    }
}
