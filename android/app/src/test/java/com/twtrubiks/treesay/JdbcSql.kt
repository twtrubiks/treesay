package com.twtrubiks.treesay

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** JVM 單元測試用的 [Sql]：sqlite-jdbc，SQL 跑在真的 SQLite 上。[file] 為 null 時是記憶體資料庫。 */
class JdbcSql(private val file: File? = null) : Sql, AutoCloseable {
    private val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:${file?.absolutePath ?: ":memory:"}")
    private var depth = 0

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement =
        connection.prepareStatement(sql).apply { args.forEachIndexed { i, v -> setObject(i + 1, v) } }

    override fun execute(sql: String, vararg args: Any?) {
        prepare(sql, args).use { it.executeUpdate() }
    }

    override fun query(sql: String, vararg args: Any): List<Row> = prepare(sql, args).use { statement ->
        statement.executeQuery().use { rs ->
            val meta = rs.metaData
            buildList {
                while (rs.next()) {
                    add(Row((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) }))
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) connection.autoCommit = false
        depth++
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            depth--
            if (depth == 0) {
                if (ok) connection.commit() else connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    override fun backup(suffix: String): String? {
        val source = file ?: return null
        val dest = File(source.parentFile, source.name + suffix)
        connection.createStatement().use { it.executeUpdate("backup to '${dest.absolutePath}'") }
        return dest.absolutePath
    }

    override fun close() = connection.close()
}
