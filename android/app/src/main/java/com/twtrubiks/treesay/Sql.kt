package com.twtrubiks.treesay

/**
 * 樹說用到的那一小撮 SQLite 操作。手機上是 [AndroidSql]，JVM 單元測試是 sqlite-jdbc——
 * 所以 [Diary] 裡的每一句 SQL 都在真的 SQLite 上測過，不靠假的資料庫。
 *
 * Android 9 內建的是 SQLite 3.22：不要用 UPSERT（3.24）、window function（3.25）、
 * `VACUUM INTO`（3.27）、`RETURNING`（3.35）這些新語法，JVM 測試用的新版 SQLite 不會替你擋下來。
 */
interface Sql {
    fun execute(sql: String, vararg args: Any?)

    /** 查詢的參數只能是字串或數字（Android 的 rawQuery 一律當字串綁），不要傳 null。 */
    fun query(sql: String, vararg args: Any): List<Row>

    /** 可以巢狀；[block] 丟例外就整個 rollback。 */
    fun <T> transaction(block: () -> T): T

    /** 把整個資料庫抄一份到資料庫旁邊，檔名接上 [suffix]，回傳抄到哪裡；記憶體裡的資料庫回 null。 */
    fun backup(suffix: String): String?
}

/** 查詢結果的一列，用欄位名取值。 */
class Row(private val values: Map<String, Any?>) {
    fun string(column: String): String = requireNotNull(stringOrNull(column)) { "$column 是 NULL" }

    fun stringOrNull(column: String): String? = values[column]?.toString()

    fun long(column: String): Long = (values[column] as Number).toLong()

    fun doubleOrNull(column: String): Double? = (values[column] as Number?)?.toDouble()
}
