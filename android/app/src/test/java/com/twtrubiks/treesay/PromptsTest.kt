package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * prompt 與 schema 跟網頁版一字不差。對照檔由 `android/scripts/prompt-fixtures.py` 用網頁版的
 * `backend/prompts.py` 產生；兩邊用同一份 cases.json 當輸入。
 */
class PromptsTest {
    private val mapper = ObjectMapper()

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource("web-prompts/$path")) { "找不到對照檔 $path" }.readText()

    private val cases: JsonNode = mapper.readTree(resource("cases.json"))

    private fun strings(node: JsonNode): List<String> = node.map { it.asText() }

    private fun diaries(node: JsonNode): List<PlantedDiary> = node.map {
        PlantedDiary(LocalDate.parse(it["date"].asText()), Emotion.normalize(it["emotion"].asText()), it["diary"].asText())
    }

    /** 網頁版改了 prompt 卻沒重新產生對照檔的話，下面的比對會拿舊的對照檔通過，所以先擋在這裡。 */
    @Test
    fun fixturesAreFromTheCurrentWebPrompts() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "backend/prompts.py") }
            .firstOrNull { it.isFile }
        requireNotNull(file) { "找不到網頁版的 backend/prompts.py" }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(
            "backend/prompts.py 改過了：在 repo 根目錄跑 python3 android/scripts/prompt-fixtures.py，再把 Prompts.kt 改到這個測試通過",
            resource("source.sha256").trim(),
            digest,
        )
    }

    @Test
    fun plantPromptsMatchWebVersion() {
        val plantCases = cases["plant"]
        assertTrue(plantCases.size() >= 4)
        for (c in plantCases) {
            val messages = c["messages"].map {
                PromptMessage(
                    time = it["time"].asText(),
                    content = it["content"].asText(),
                    photo = it["photo"]?.asInt(),
                    photoUnseen = it["photo_unseen"]?.asBoolean() ?: false,
                )
            }
            val actual = Prompts.plantPrompt(
                messages,
                question = c["question"].takeUnless { it.isNull }?.asText(),
                daysAgo = c["days_ago"].asLong(),
                memories = strings(c["memories"]),
                recentDiaries = diaries(c["recent_diaries"]),
            )
            assertEquals(c["name"].asText(), resource("expected/${c["name"].asText()}.txt"), actual)
        }
    }

    @Test
    fun weeklyPromptsMatchWebVersion() {
        for (c in cases["weekly"]) {
            val actual = Prompts.weeklyPrompt(diaries(c["diaries"]), memories = strings(c["memories"]))
            assertEquals(c["name"].asText(), resource("expected/${c["name"].asText()}.txt"), actual)
        }
    }

    @Test
    fun schemasMatchWebVersion() {
        val web = mapper.readTree(resource("schemas.json"))
        assertEquals(web["plant"], Prompts.PLANT_SCHEMA)
        assertEquals(web["weekly_with_memories"], Prompts.weeklySchema(withMemories = true))
        assertEquals(web["weekly_without_memories"], Prompts.weeklySchema(withMemories = false))
    }

    /** structured outputs 的要求：每個 object 全欄位 required、不能有多餘欄位，也不支援 maxItems／maxLength。 */
    @Test
    fun objectsAreClosedAndFullyRequired() {
        for (schema in listOf(Prompts.PLANT_SCHEMA, Prompts.weeklySchema(true), Prompts.weeklySchema(false))) {
            assertEquals("object", schema["type"].asText())
            assertFalse(schema["additionalProperties"].asBoolean())
            assertEquals(schema["properties"].fieldNames().asSequence().toList(), strings(schema["required"]))
            val text = schema.toString()
            assertFalse(text.contains("maxItems") || text.contains("maxLength"))
        }
    }

    @Test
    fun emotionEnumMatchesTheSevenEmotions() {
        assertEquals(Emotion.entries.map { it.key }, strings(Prompts.PLANT_SCHEMA["properties"]["emotion"]["enum"]))
    }

    @Test
    fun weeklyMemoriesOnlyWhenThereAreMemories() {
        assertTrue(Prompts.weeklySchema(true)["properties"].has("memories"))
        assertFalse(Prompts.weeklySchema(false)["properties"].has("memories"))
        assertFalse(Prompts.weeklyPrompt(emptyList()).contains("memories"))
    }

    @Test
    fun dayWordSpeaksLikeAPerson() {
        assertEquals(listOf("今天", "昨天", "前天", "5 天前"), listOf(0L, 1L, 2L, 5L).map(Prompts::dayWord))
    }
}
