package com.twtrubiks.treesay

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * 手機上的 [Sql]：App 私有空間裡的 `treesay.db`。表的建立與升級由 [Diary] 負責（`CREATE TABLE IF NOT EXISTS`），
 * 這裡只管開檔。`allowBackup="false"`，整本日記不會被系統自動備份到雲端。
 */
class AndroidSql(context: Context, name: String = NAME) : Sql {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }.apply {
        // 不用 WAL（Android 9 預設會偷偷開）：沒有寫到一半的 transaction 時，主檔本身就是完整的，備份抄一個檔就夠
        setWriteAheadLoggingEnabled(false)
    }
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val file: File = context.getDatabasePath(name)

    override fun execute(sql: String, vararg args: Any?) = db.execSQL(sql, args)

    override fun query(sql: String, vararg args: Any): List<Row> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Row((0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.value(it) }))
                }
            }
        }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    override fun <T> transaction(block: () -> T): T {
        val db = db
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Android 9 的 SQLite 沒有 `VACUUM INTO`，直接抄整個檔案。抄的這段時間握著一個 exclusive transaction，
     * 沒有人能寫進來，抄到的不會是寫到一半的頁面。
     */
    override fun backup(suffix: String): String {
        val dest = File(file.parentFile, file.name + suffix)
        transaction { file.copyTo(dest, overwrite = true) }
        return dest.absolutePath
    }

    companion object {
        const val NAME = "treesay.db"
    }
}
