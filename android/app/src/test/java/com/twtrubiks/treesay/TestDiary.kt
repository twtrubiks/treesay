package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

val TAIPEI: ZoneOffset = ZoneOffset.ofHours(8)

fun taipei(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): OffsetDateTime =
    OffsetDateTime.of(year, month, day, hour, minute, 0, 0, TAIPEI)

private val mapper = ObjectMapper()

fun json(text: String): ObjectNode = mapper.readTree(text) as ObjectNode

/** 一次種樹的標準回覆，欄位可以個別換掉。 */
fun plantReply(
    diary: String = "今天去河堤跑了一圈。",
    emotion: Any? = "happy",
    treeReply: String = "跑完很舒服吧，謝謝你告訴我。",
    memory: Any? = emptyList<String>(),
    keywords: Any? = listOf("跑步"),
): ObjectNode = mapper.valueToTree<ObjectNode>(
    mapOf("diary" to diary, "emotion" to emotion, "tree_reply" to treeReply, "memory" to memory, "keywords" to keywords),
)

/** 假的 Claude：記下每一次呼叫，回覆由 [reply] 決定（預設是一次正常的種樹）。 */
class FakeAi : TreeAi {
    class Call(val prompt: String, val schema: ObjectNode, val images: List<AiImage>, val purpose: Purpose)

    val calls = mutableListOf<Call>()
    var reply: (Call) -> ObjectNode = { plantReply() }

    val lastPrompt: String get() = calls.last().prompt

    override suspend fun ask(prompt: String, schema: ObjectNode, images: List<AiImage>, purpose: Purpose): ObjectNode {
        val call = Call(prompt, schema, images, purpose)
        calls += call
        return reply(call)
    }
}

/**
 * 測試用的日記本：真的 SQLite（sqlite-jdbc）、暫存資料夾裡的照片、可以撥動的時鐘、假的 Claude。
 * 照片的「縮圖」只認檔頭，認得的原樣送出，跟網頁版「夠小、方向正確、格式認得就原檔照送」一樣。
 */
class TestDiary(root: File) : AutoCloseable {
    var now: OffsetDateTime = taipei(2026, 7, 24, 12)
    val ai = FakeAi()
    val sql = JdbcSql(File(root, "treesay.db"))
    val photos = File(root, "photos")
    val diary = Diary(
        sql = sql,
        photosDir = photos,
        ai = ai,
        prepareImage = { bytes -> Photos.sniffMediaType(bytes)?.let { AiImage(bytes, it) } },
        now = { now },
    )

    val today: LocalDate get() = TreeDay.effectiveDate(now.toLocalDateTime())

    fun send(content: String, photo: NewPhoto? = null) = diary.send(content, photo)

    fun plant(date: LocalDate = today) = runBlocking { diary.plant(date) }

    fun report(weekStart: LocalDate) = runBlocking { diary.report(weekStart) }

    /** 在 [date] 那天的中午說話並種下（不動目前的時鐘）。 */
    fun plantedDay(date: LocalDate, vararg messages: String, reply: ObjectNode = plantReply()): DayView {
        val back = now
        now = OffsetDateTime.of(date.atTime(12, 0), TAIPEI)
        try {
            messages.forEach { send(it) }
            val previous = ai.reply
            ai.reply = { reply }
            try {
                return plant(date)
            } finally {
                ai.reply = previous
            }
        } finally {
            now = back
        }
    }

    /** 直接看資料庫裡的那一列（含墓碑），確認畫面看不到的東西。 */
    fun rows(table: String, where: String = "1 = 1", vararg args: Any) = sql.query("SELECT * FROM $table WHERE $where", *args)

    override fun close() = sql.close()
}

val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
val HEIC = "....ftypheic".toByteArray()

inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) return e
        throw AssertionError("預期 ${E::class.simpleName}，實際是 $e", e)
    }
    fail("預期 ${E::class.simpleName}，結果沒有丟例外")
    throw IllegalStateException()
}

/** 預期被擋下，並檢查給使用者看的那句話。 */
fun assertRefused(message: String, block: () -> Unit): TreeSayException =
    assertThrows<TreeSayException>(block).also { assertEquals(message, it.message) }
