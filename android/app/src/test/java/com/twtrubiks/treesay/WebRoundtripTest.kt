package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 手機與電腦版的匯出檔互相讀得懂。兩個方向都用網頁版真的後端產生或檢查（`scripts/web-roundtrip.py`）：
 * - 電腦 → 手機：`resources/web-export/export.json` 是網頁版走一輪之後 `/api/export` 的原樣輸出
 * - 手機 → 電腦：這裡寫出 `build/web-check/android-export.json`，CI 跑完單元測試後匯進網頁版檢查
 */
class WebRoundtripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mapper = ObjectMapper()
    private val phone by lazy { TestDiary(tmp.newFolder("phone")) }

    @After
    fun close() = phone.close()

    private val web: JsonNode by lazy {
        mapper.readTree(requireNotNull(javaClass.getResource("/web-export/export.json")) { "缺對照檔，先跑 scripts/web-roundtrip.py fixture" })
    }

    /** 網頁版存當地時間、不帶時差；手機帶時差。比的是說話當地牆上的時鐘。 */
    private fun wallClock(text: String?): LocalDateTime? = text?.let {
        runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrElse { _ -> LocalDateTime.parse(text) }
    }

    private fun JsonNode.textOrNull(): String? = if (isNull || isMissingNode) null else asText()

    @Test
    fun exportFromTheWebComesInWhole() {
        val result = phone.diary.import(web.toString())
        assertEquals(web["days"].size(), result.daysAdded)
        assertEquals(web["weekly_reports"].size(), result.reportsAdded)
        assertEquals(web["memories"].size(), result.memoriesAdded)
        assertEquals(web["days"].sumOf { d -> d["messages"].count { !it["photo"].isNull } }, result.photosMissing)

        for (w in web["days"]) {
            val date = LocalDate.parse(w["date"].asText())
            val day = requireNotNull(phone.diary.day(date)) { "$date 沒有進來" }
            assertEquals(w["status"].asText(), day.status.name.lowercase())
            assertEquals(w["question"].asText(), day.question)
            assertEquals(w["diary"].textOrNull(), day.diary)
            assertEquals(w["emotion"].textOrNull(), day.emotion?.key)
            assertEquals(w["tree_reply"].textOrNull(), day.treeReply)
            assertEquals(w["keywords"].map { it.asText() }, day.keywords)
            assertEquals(wallClock(w["planted_at"].textOrNull()), day.plantedAt?.toLocalDateTime())
            assertEquals(
                w["messages"].map { Triple(it["content"].asText(), it["photo"].textOrNull(), wallClock(it["created_at"].asText())) },
                day.messages.map { Triple(it.content, it.photo, it.createdAt.toLocalDateTime()) },
            )
        }

        // 週報是快取：讀得出來，而且不會再請 Claude 整理一次
        for (w in web["weekly_reports"]) {
            val ready = phone.report(LocalDate.parse(w["week_start"].asText())) as WeeklyResult.Ready
            val r = w["report"]
            assertEquals(r["good_things"].map { it.asText() }, ready.report.goodThings)
            assertEquals(r["bad_things"].map { it.asText() }, ready.report.badThings)
            assertEquals(r["keywords"].map { it.asText() }, ready.report.keywords)
            assertEquals(r["advice"].asText(), ready.report.advice)
        }
        assertTrue(phone.ai.calls.isEmpty())
        assertEquals(
            web["memories"].map { it["content"].asText() to LocalDate.parse(it["source_date"].asText()) }.toSet(),
            phone.diary.memories().map { it.content to it.sourceDate }.toSet(),
        )

        // 手機再匯出一次，內容跟電腦版的一樣（只差時間多帶了時差）
        val again = mapper.readTree(phone.diary.export())
        for ((w, a) in web["days"].zip(again["days"])) {
            for (key in listOf("date", "status", "question", "diary", "emotion", "tree_reply", "keywords")) {
                assertEquals("${w["date"]} 的 $key", w[key], a[key])
            }
            assertEquals(
                w["messages"].map { it["content"] to wallClock(it["created_at"].asText()) },
                a["messages"].map { it["content"] to wallClock(it["created_at"].asText()) },
            )
        }
        assertEquals(web["weekly_reports"].map { it["report"] }, again["weekly_reports"].map { it["report"] })
    }

    /**
     * 手機上能發生的事都走一遍，匯出給網頁版檢查：附照片、改字、收回、出國（東京 +09:00、凌晨 2 點還算前一天）、
     * 補種、週報整理記憶、放下記憶。收回的話與放下的記憶只留墓碑，不能跟著匯出去。
     */
    @Test
    fun writesAnExportForTheWebToCheck() {
        val tokyo = ZoneOffset.ofHours(9)
        phone.now = taipei(2026, 7, 13, 8, 10)
        phone.send("早上去河堤跑了一圈", NewPhoto(JPEG, ".jpg"))
        val typo = phone.send("中午吃了牛肉麵，有點鹹")
        phone.diary.edit(typo.id, "中午吃了牛肉麵，湯有點鹹")
        phone.diary.retract(phone.send("這句說錯了").id)
        phone.now = taipei(2026, 7, 13, 22)
        phone.ai.reply = { plantReply(memory = listOf("週末喜歡去河堤跑步"), keywords = listOf("河堤", "牛肉麵")) }
        phone.plant()

        phone.now = OffsetDateTime.of(2026, 7, 14, 23, 30, 0, 0, tokyo)
        phone.send("到東京了，飯店好小")
        phone.now = OffsetDateTime.of(2026, 7, 15, 2, 0, 0, 0, tokyo)
        phone.send("睡不著，凌晨兩點還算昨天")

        phone.now = OffsetDateTime.of(2026, 7, 15, 12, 0, 0, 0, tokyo)
        phone.send("在淺草吃了糰子")
        phone.now = taipei(2026, 7, 16, 20)
        phone.ai.reply = { plantReply(diary = "在淺草吃了糰子。", emotion = "excited", memory = listOf("最近在準備搬家"), keywords = listOf("淺草")) }
        phone.plant(LocalDate.of(2026, 7, 15))

        phone.now = taipei(2026, 7, 20, 9)
        phone.ai.reply = {
            json(
                """{"good_things":["在河堤跑了一圈","去了東京"],"bad_things":["飯店好小"],"keywords":["河堤","東京"],
                   "advice":"下週也照自己的步調來。","memories":["週末喜歡去河堤跑步","最近在準備搬家","出國時容易失眠"]}""",
            )
        }
        phone.report(LocalDate.of(2026, 7, 13))
        phone.diary.letGo(phone.diary.memories().first { it.content == "出國時容易失眠" }.id)
        phone.now = taipei(2026, 7, 20, 21)
        phone.send("今天還沒種的一天")

        val exported = phone.diary.export()
        val data = mapper.readTree(exported)
        assertEquals(listOf("2026-07-13", "2026-07-14", "2026-07-15", "2026-07-20"), data["days"].map { it["date"].asText() })
        assertEquals("2026-07-15T02:00:00+09:00", data["days"][1]["messages"][1]["created_at"].asText())
        assertTrue("收回的話不能匯出去", "這句說錯了" !in exported)
        assertTrue("放下的記憶不能匯出去", "出國時容易失眠" !in exported)

        // Gradle 跑單元測試的工作目錄是 app/；scripts/web-roundtrip.py check 讀這個檔
        val out = File("build/web-check/android-export.json")
        out.parentFile.mkdirs()
        out.writeText(exported)
    }
}
