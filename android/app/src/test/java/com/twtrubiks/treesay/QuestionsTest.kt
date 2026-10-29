package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class QuestionsTest {

    @Test
    fun sameDay_sameQuestion() {
        val day = LocalDate.of(2026, 10, 28)
        assertEquals(Questions.questionFor(day), Questions.questionFor(day))
    }

    /** 期望值由網頁版 `questions.question_for` 算出：兩邊的定題方式要一樣，同一天才問同一題。 */
    @Test
    fun sameQuestionAsWebVersionOnTheSameDay() {
        assertEquals("今天走了多少路？沿途有什麼記得的畫面嗎？", Questions.questionFor(LocalDate.of(2026, 7, 24)))
        assertEquals("最近有沒有什麼話，一直放在心裡沒說出口？", Questions.questionFor(LocalDate.of(2026, 10, 28)))
        assertEquals("今天有沒有替別人做什麼小事？或別人替你？", Questions.questionFor(LocalDate.of(2027, 1, 1)))
    }

    /** 題庫兩邊各存一份，加題要兩邊一起加、加在同一個位置，否則同一天會問不同題。 */
    @Test
    fun bankMatchesWebVersion() {
        assertEquals(webQuestionBank(), Questions.ALL)
    }

    private fun webQuestionBank(): List<String> {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "backend/questions.py") }
            .firstOrNull { it.isFile }
        requireNotNull(file) { "找不到網頁版的 backend/questions.py" }
        val body = file.readText().substringAfter("QUESTIONS = [").substringBefore("]")
        return Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
            .also { require(it.isNotEmpty()) { "讀不到網頁版的題庫，questions.py 的格式可能變了" } }
    }
}
