package com.copiosa.data.anki

import java.sql.Connection

/** Test-only [SqlExec] that routes the shared schema/insert logic through a JDBC connection. */
class JdbcSqlExec(private val conn: Connection) : SqlExec {
    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun insert(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
    }
}
