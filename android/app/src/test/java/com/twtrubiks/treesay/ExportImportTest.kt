package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 照搬 test_api.py 的 TestExport、TestImport。格式對齊網頁版 `/api/export`：
 * 手機匯出、電腦匯入（只加不蓋），就是同步做出來前的手動同步。
 */
class ExportImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mapper = ObjectMapper()
    private val source by lazy { TestDiary(tmp.newFolder("phone")) }
    private val target by lazy { TestDiary(tmp.newFolder("other")) }

    @After
    fun close() {
        source.close()
        target.close()
    }

    private fun exported(t: TestDiary = source): JsonNode = mapper.readTree(t.diary.export())

    @Test
    fun emptyExportIsValid() {
        val data = exported()
        assertEquals(listOf("exported_at", "data_dir", "days", "weekly_reports", "memories"), data.fieldNames().asSequence().toList())
        assertEquals(0, data["days"].size())
        assertEquals(source.now.toString().length > 0, data["exported_at"].asText().startsWith("2026-07-24T12:00:00+08:00"))
    }

    @Test
    fun exportCarriesPlantedAndUnplantedDaysButNotRetractedWords() {
        source.plantedDay(
            LocalDate.of(2026, 7, 23),
            "種下的那天",
            reply = plantReply(memory = listOf("在準備搬家"), keywords = listOf("搬家")),
        )
        source.send("還沒收尾")
        source.diary.retract(source.send("收回的話").id)

        val days = exported()["days"]
        assertEquals(2, days.size())
        val planted = days[0]
        assertEquals("2026-07-23", planted["date"].asText())
        assertEquals("planted", planted["status"].asText())
        assertEquals(Questions.questionFor(LocalDate.of(2026, 7, 23)), planted["question"].asText())
        assertEquals("今天去河堤跑了一圈。", planted["diary"].asText())
        assertEquals("happy", planted["emotion"].asText())
        assertEquals("搬家", planted["keywords"][0].asText())
        assertEquals("2026-07-23T12:00:00+08:00", planted["planted_at"].asText())
        assertEquals("種下的那天", planted["messages"][0]["content"].asText())
        assertEquals("2026-07-23T12:00:00+08:00", planted["messages"][0]["created_at"].asText())
        assertTrue(planted["messages"][0]["photo"].isNull)

        val open = days[1]
        assertEquals("collecting", open["status"].asText())
        assertTrue(open["diary"].isNull)
        assertEquals(listOf("還沒收尾"), open["messages"].map { it["content"].asText() })

        val memory = exported()["memories"].single()
        assertEquals("在準備搬家", memory["content"].asText())
        assertEquals("2026-07-23", memory["source_date"].asText())
    }

    @Test
    fun roundtripIntoAnEmptyPhone() {
        source.plantedDay(LocalDate.of(2026, 7, 13), "週一", reply = plantReply(memory = listOf("養了一隻貓")))
        source.ai.reply = { json("""{"good_things":["好事"],"bad_things":[],"keywords":["貓"],"advice":"慢慢來"}""") }
        source.report(LocalDate.of(2026, 7, 13))
        source.send("今天", NewPhoto(JPEG, ".jpg"))

        val result = target.diary.import(source.diary.export())
        assertEquals(ImportResult(result.backup, 2, 0, 1, 0, 1, 0, photosMissing = 1), result)

        assertEquals(source.diary.month(YearMonth.of(2026, 7)), target.diary.month(YearMonth.of(2026, 7)))
        val day = target.diary.day(LocalDate.of(2026, 7, 13))!!
        val original = source.diary.day(LocalDate.of(2026, 7, 13))!!
        assertEquals(original.copy(messages = emptyList()), day.copy(messages = emptyList()))
        assertEquals(original.messages.map { it.content to it.createdAt }, day.messages.map { it.content to it.createdAt })
        assertEquals(listOf("養了一隻貓"), target.diary.memories().map { it.content })
        assertTrue(target.report(LocalDate.of(2026, 7, 13)) is WeeklyResult.Ready)
        assertTrue(target.ai.calls.isEmpty())
    }

    /** 只加不蓋：手機上已經有的那一天、那一週、同一條記憶都跳過，其餘照常匯入。 */
    @Test
    fun existingDaysAreKeptAndTheRestStillComeIn() {
        source.plantedDay(LocalDate.of(2026, 7, 22), "電腦上的那天", reply = plantReply(memory = listOf("在準備搬家")))
        source.plantedDay(LocalDate.of(2026, 7, 23), "電腦上的另一天")
        target.plantedDay(LocalDate.of(2026, 7, 22), "手機上的那天", reply = plantReply(diary = "手機寫的日記", memory = listOf("在準備搬家")))

        val result = target.diary.import(source.diary.export())
        assertEquals(1, result.daysAdded)
        assertEquals(1, result.daysSkipped)
        assertEquals(0, result.memoriesAdded)
        assertEquals(1, result.memoriesSkipped)
        assertEquals("手機寫的日記", target.diary.day(LocalDate.of(2026, 7, 22))!!.diary)
        assertEquals(listOf("手機上的那天"), target.diary.day(LocalDate.of(2026, 7, 22))!!.messages.map { it.content })
        assertEquals(DayStatus.PLANTED, target.diary.day(LocalDate.of(2026, 7, 23))!!.status)
    }

    @Test
    fun importedQuestionIsKeptNotRecomputed() {
        val result = target.diary.import(
            """{"days":[{"date":"2026-07-01","status":"collecting","question":"那天真正問過的題目",
               "messages":[{"content":"嗨","created_at":"2026-07-01T09:00:00+08:00"}]},
               {"date":"2026-07-02","messages":[{"content":"沒帶問題的舊資料","created_at":"2026-07-02T09:00:00+08:00"}]}]}""",
        )
        assertEquals(2, result.daysAdded)
        assertEquals("那天真正問過的題目", target.diary.day(LocalDate.of(2026, 7, 1))!!.question)
        assertEquals(Questions.questionFor(LocalDate.of(2026, 7, 2)), target.rows("days", "date = '2026-07-02'").single().string("question"))
    }

    /** 動筆之前先把整個資料庫抄一份放在旁邊，要還原就把它改回 treesay.db。 */
    @Test
    fun backupWrittenBeforeImport() {
        target.send("匯入前就在的話")
        val result = target.diary.import("""{"days":[]}""")
        val backup = File(requireNotNull(result.backup))
        assertTrue(backup.name.startsWith("treesay.db.bak-20260724-120000"))
        JdbcSql(backup).use { assertEquals(1, it.query("SELECT * FROM messages").size) }
    }

    @Test
    fun garbageFileIsRejectedAndNothingChanges() {
        target.send("原本的話")
        for (bad in listOf("這不是 JSON", "[1, 2]", """{"days":"不是清單"}""", """{"days":[{"status":"planted"}]}""",
            """{"days":[{"date":"2026-07-01","messages":[{"content":"沒有時間"}]}]}""", """{"memories":[{"content":"沒有日期"}]}""")) {
            assertRefused(Diary.IMPORT_UNREADABLE) { target.diary.import(bad) }
        }
        assertEquals(1, target.rows("days").size)
        assertEquals(1, target.rows("messages").size)
    }

    @Test
    fun plantedDayWithBadEmotionFallsBackToCalm() {
        target.diary.import(
            """{"days":[{"date":"2026-07-01","status":"planted","diary":"日記","emotion":"ecstatic","tree_reply":"回覆",
               "keywords":["k", 3],"messages":[]}]}""",
        )
        val day = target.diary.day(LocalDate.of(2026, 7, 1))!!
        assertEquals(Emotion.CALM, day.emotion)
        assertEquals(listOf("k"), day.keywords)
    }

    /** 匯出當下卡在 planting、或號稱種下卻沒有日記的那天，進來當作還在收集。 */
    @Test
    fun halfPlantedDayComesBackAsCollecting() {
        target.diary.import(
            """{"days":[{"date":"2026-07-01","status":"planting","messages":[{"content":"嗨","created_at":"2026-07-01T09:00:00"}]},
               {"date":"2026-07-02","status":"planted","diary":"","tree_reply":"回覆","messages":[]}]}""",
        )
        for (date in listOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2))) {
            val day = target.diary.day(date)!!
            assertEquals(DayStatus.COLLECTING, day.status)
            assertNull(day.diary)
            assertNull(day.treeReply)
        }
    }

    /** 照片不在 JSON 裡：檔名照存不丟（丟了就永遠接不回來），只回報缺幾張。 */
    @Test
    fun missingPhotoIsCountedButKept() {
        File(target.photos, "2026-07-01").mkdirs()
        File(target.photos, "2026-07-01/here.jpg").writeBytes(JPEG)
        val result = target.diary.import(
            """{"days":[{"date":"2026-07-01","messages":[
               {"content":"在","photo":"2026-07-01/here.jpg","created_at":"2026-07-01T09:00:00"},
               {"content":"不在","photo":"2026-07-01/gone.jpg","created_at":"2026-07-01T10:00:00"}]}]}""",
        )
        assertEquals(1, result.photosMissing)
        assertEquals(listOf("2026-07-01/here.jpg", "2026-07-01/gone.jpg"), target.diary.day(LocalDate.of(2026, 7, 1))!!.messages.map { it.photo })
    }

    /**
     * 匯入檔裡的照片路徑跑出 photos 資料夾的不認：匯入的那天若是今天，收回訊息會去刪照片，
     * 一個 `../treesay.db` 就能把整本日記刪掉。
     */
    @Test
    fun photoPathEscapingThePhotosFolderIsDropped() {
        val result = target.diary.import(
            """{"days":[{"date":"2026-07-24","messages":[{"content":"嗨","photo":"../treesay.db","created_at":"2026-07-24T09:00:00"}]}]}""",
        )
        assertEquals(1, result.photosMissing)
        val message = target.diary.today().day.messages.single()
        assertNull(message.photo)
        target.diary.retract(message.id)
        assertTrue(File(target.photos.parentFile, "treesay.db").isFile)
    }

    @Test
    fun weeklyReportImportedOnce() {
        val payload = """{"weekly_reports":[{"week_start":"2026-07-13","report":{"good_things":["好事"],"bad_things":[],"keywords":[],"advice":"慢慢來"},"created_at":"2026-07-21T10:00:00"}]}"""
        assertEquals(1, target.diary.import(payload).reportsAdded)
        val again = target.diary.import(payload)
        assertEquals(0, again.reportsAdded)
        assertEquals(1, again.reportsSkipped)
        val ready = target.report(LocalDate.of(2026, 7, 13)) as WeeklyResult.Ready
        assertEquals("慢慢來", ready.report.advice)
        // 網頁版的時間沒帶時差，當成這支手機現在的時差
        assertEquals(taipei(2026, 7, 21, 10), ready.generatedAt)
    }

    /** 網頁版 `/api/export` 原樣吃得下：時間沒帶時差、記憶的 id 是整數、多出 data_dir 之類的欄位。 */
    @Test
    fun webExportIsAccepted() {
        val web = """
            {
              "exported_at": "2026-07-24T21:00:00.123456",
              "data_dir": "/home/me/.treesay",
              "days": [{
                "date": "2026-07-20", "status": "planted", "question": "那天的題目",
                "diary": "電腦上寫的日記", "emotion": "excited", "tree_reply": "樹的回覆",
                "keywords": ["電腦"], "planted_at": "2026-07-20T22:00:00.5",
                "messages": [{"content": "在電腦上說的", "photo": "2026-07-20/abc.jpg", "created_at": "2026-07-20T21:05:30.123456"}]
              }],
              "weekly_reports": [],
              "memories": [{"id": 1, "content": "在準備搬家", "source_date": "2026-07-20", "created_at": "2026-07-20T22:00:00"}]
            }
        """
        val result = target.diary.import(web)
        assertEquals(1, result.daysAdded)
        assertEquals(1, result.memoriesAdded)
        val day = target.diary.day(LocalDate.of(2026, 7, 20))!!
        assertEquals(Emotion.EXCITED, day.emotion)
        assertEquals(21, day.messages.single().createdAt.hour)
        assertEquals(TAIPEI, day.messages.single().createdAt.offset)
        assertNotNull(day.plantedAt)
        assertFalse(target.diary.memories().isEmpty())
    }
}
