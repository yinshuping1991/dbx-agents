package com.dbx.agent

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

object JdbcExecutor {
    const val DEFAULT_MAX_ROWS = 10000

    fun execute(
        conn: Connection,
        sql: String,
        schema: String?,
        setSchemaSql: (String) -> String,
        maxRows: Int = DEFAULT_MAX_ROWS,
        valueReader: (ResultSet, Int, Int) -> Any? = ::defaultResultValue,
    ): QueryResult {
        val trimmedSql = sql.trim().trimEnd(';')
        val upperSql = trimmedSql.uppercase().trimStart()
        val start = System.currentTimeMillis()

        when (upperSql) {
            "BEGIN", "BEGIN TRANSACTION" -> {
                conn.autoCommit = false
                return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
            "COMMIT" -> {
                conn.commit()
                conn.autoCommit = true
                return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
            "ROLLBACK" -> {
                conn.rollback()
                conn.autoCommit = true
                return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
        }

        if (!schema.isNullOrBlank()) {
            val schemaSql = setSchemaSql(schema)
            if (schemaSql.isNotBlank()) {
                conn.createStatement().use { it.execute(schemaSql) }
            }
        }

        conn.createStatement().use { stmt ->
            stmt.maxRows = maxRows + 1
            val hasResultSet = stmt.execute(trimmedSql)
            val elapsed = System.currentTimeMillis() - start
            if (hasResultSet) {
                stmt.resultSet.use { rs ->
                    return readResultSet(rs, elapsed, maxRows, valueReader)
                }
            }

            val updateCount = stmt.updateCount
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                affected_rows = if (updateCount >= 0) updateCount.toLong() else 0,
                execution_time_ms = elapsed,
                truncated = false,
            )
        }
    }

    fun readResultSet(
        rs: ResultSet,
        executionTimeMs: Long,
        maxRows: Int = DEFAULT_MAX_ROWS,
        valueReader: (ResultSet, Int, Int) -> Any? = ::defaultResultValue,
    ): QueryResult {
        val meta = rs.metaData
        val colCount = meta.columnCount
        val columns = (1..colCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<List<Any?>>()
        var truncated = false

        while (rs.next()) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            val row = (1..colCount).map { i -> valueReader(rs, i, meta.getColumnType(i)) }
            rows.add(row)
        }

        return QueryResult(
            columns = columns,
            rows = rows,
            affected_rows = 0,
            execution_time_ms = executionTimeMs,
            truncated = truncated,
        )
    }

    fun defaultResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
        val value = when (sqlType) {
            Types.BIGINT -> rs.getLong(index)
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rs.getInt(index)
            Types.FLOAT, Types.REAL -> rs.getFloat(index)
            Types.DOUBLE -> rs.getDouble(index)
            Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(index)
            Types.BOOLEAN, Types.BIT -> rs.getBoolean(index)
            else -> rs.getObject(index)
        }
        return if (rs.wasNull()) null else value
    }
}

object JdbcIdentifiers {
    fun doubleQuote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
    fun backtick(identifier: String): String = "`${identifier.replace("`", "``")}`"
}
