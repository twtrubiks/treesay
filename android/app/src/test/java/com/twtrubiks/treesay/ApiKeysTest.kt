package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysTest {

    /** 從信件或筆記貼過來常夾帶換行與空白。 */
    @Test
    fun pastedWhitespaceRemoved() {
        assertEquals("sk-ant-api03-abcd", ApiKeys.sanitize("  sk-ant-api03-\nabcd \t"))
        assertEquals("sk-ant-api03-abcd", ApiKeys.sanitize("　sk-ant-api03-abcd　"))
    }

    @Test
    fun onlyTheLastFourShown() {
        assertEquals("••••wxyz", ApiKeys.masked("sk-ant-api03-0123456789wxyz"))
    }

    @Test
    fun shortKeyNotRevealed() {
        assertEquals("••••", ApiKeys.masked("sk-ant-1"))
    }
}
