package com.dbx.agent

import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object JdbcExecutor {
    const val DEFAULT_MAX_ROWS = 10000
    const val QUERY_SESSION_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
    private val sessions = ConcurrentHashMap<String, QuerySession>()

    fun execute(
        conn: Connection,
        sql: String,
        schema: String?,
        setSchemaSql: (String) -> String,
        maxRows: Int = DEFAULT_MAX_ROWS,
        fetchSize: Int? = null,
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
            val effectiveMaxRows = maxRows.coerceAtLeast(1)
            stmt.maxRows = effectiveMaxRows + 1
            if (fetchSize != null && fetchSize > 0) {
                stmt.fetchSize = fetchSize
            }
            val hasResultSet = stmt.execute(trimmedSql)
            val elapsed = System.currentTimeMillis() - start
            if (hasResultSet) {
                stmt.resultSet.use { rs ->
                    return readResultSet(rs, elapsed, effectiveMaxRows, valueReader)
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

    fun executePage(
        conn: Connection,
        sql: String,
        schema: String?,
        setSchemaSql: (String) -> String,
        options: QueryPageOptions = QueryPageOptions(),
        valueReader: (ResultSet, Int, Int) -> Any? = ::defaultResultValue,
    ): QueryPageResult {
        expireIdleQuerySessions()
        val trimmedSql = sql.trim().trimEnd(';')
        val upperSql = trimmedSql.uppercase().trimStart()
        val start = System.currentTimeMillis()

        when (upperSql) {
            "BEGIN", "BEGIN TRANSACTION" -> {
                conn.autoCommit = false
                return QueryPageResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
            "COMMIT" -> {
                conn.commit()
                conn.autoCommit = true
                return QueryPageResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
            "ROLLBACK" -> {
                conn.rollback()
                conn.autoCommit = true
                return QueryPageResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
            }
        }

        if (!schema.isNullOrBlank()) {
            val schemaSql = setSchemaSql(schema)
            if (schemaSql.isNotBlank()) {
                conn.createStatement().use { it.execute(schemaSql) }
            }
        }

        val stmt = conn.createStatement()
        try {
            if (options.fetchSize != null && options.fetchSize > 0) {
                stmt.fetchSize = options.fetchSize
            }
            val hasResultSet = stmt.execute(trimmedSql)
            val elapsed = System.currentTimeMillis() - start
            if (!hasResultSet) {
                val updateCount = stmt.updateCount
                stmt.close()
                return QueryPageResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    affected_rows = if (updateCount >= 0) updateCount.toLong() else 0,
                    execution_time_ms = elapsed,
                )
            }

            val rs = stmt.resultSet
            val meta = rs.metaData
            val sessionId = UUID.randomUUID().toString()
            val session = QuerySession(
                id = sessionId,
                statement = stmt,
                resultSet = rs,
                columns = resultColumns(meta),
                maxRows = options.maxRows.coerceAtLeast(1),
                valueReader = valueReader,
            )
            sessions[sessionId] = session
            return readSessionPage(session, options.pageSize, elapsed)
        } catch (e: Exception) {
            runCatching { stmt.close() }
            throw e
        }
    }

    fun fetchPage(sessionId: String, pageSize: Int): QueryPageResult {
        expireIdleQuerySessions()
        val session = sessions[sessionId] ?: throw IllegalArgumentException("Query session not found")
        return synchronized(session) {
            readSessionPage(session, pageSize, 0)
        }
    }

    fun closeQuerySession(sessionId: String): Boolean {
        return closeSession(sessionId)
    }

    fun closeAllQuerySessions() {
        sessions.keys.toList().forEach { closeSession(it) }
    }

    fun expireIdleQuerySessions(
        nowMillis: Long = System.currentTimeMillis(),
        idleTimeoutMillis: Long = QUERY_SESSION_IDLE_TIMEOUT_MILLIS,
    ): Int {
        if (idleTimeoutMillis < 0) return 0
        var closed = 0
        sessions.values.toList().forEach { session ->
            val expired = synchronized(session) {
                nowMillis - session.lastAccessedAtMillis >= idleTimeoutMillis
            }
            if (expired && closeSession(session.id)) {
                closed += 1
            }
        }
        return closed
    }

    private fun readSessionPage(session: QuerySession, pageSize: Int, executionTimeMs: Long): QueryPageResult {
        session.lastAccessedAtMillis = System.currentTimeMillis()
        val effectivePageSize = pageSize.coerceAtLeast(1)
        val rows = mutableListOf<List<Any?>>()
        var truncated = false

        session.pendingRow?.let {
            rows.add(it)
            session.pendingRow = null
        }

        while (rows.size < effectivePageSize && session.rowsRead < session.maxRows) {
            if (!session.resultSet.next()) {
                closeSession(session.id)
                return QueryPageResult(
                    columns = session.columns,
                    rows = rows,
                    affected_rows = 0,
                    execution_time_ms = executionTimeMs,
                    truncated = false,
                    session_id = null,
                    has_more = false,
                )
            }
            rows.add(rowValues(session.resultSet, session.valueReader))
            session.rowsRead += 1
        }

        if (session.rowsRead >= session.maxRows) {
            truncated = session.resultSet.next()
            closeSession(session.id)
            return QueryPageResult(
                columns = session.columns,
                rows = rows,
                affected_rows = 0,
                execution_time_ms = executionTimeMs,
                truncated = truncated,
                session_id = null,
                has_more = false,
            )
        }

        val hasMore = session.resultSet.next()
        if (!hasMore) {
            closeSession(session.id)
            return QueryPageResult(
                columns = session.columns,
                rows = rows,
                affected_rows = 0,
                execution_time_ms = executionTimeMs,
                truncated = false,
                session_id = null,
                has_more = false,
            )
        }

        session.pendingRow = rowValues(session.resultSet, session.valueReader)
        session.rowsRead += 1
        return QueryPageResult(
            columns = session.columns,
            rows = rows,
            affected_rows = 0,
            execution_time_ms = executionTimeMs,
            truncated = false,
            session_id = session.id,
            has_more = true,
        )
    }

    private fun closeSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        synchronized(session) {
            runCatching { session.resultSet.close() }
            runCatching { session.statement.close() }
        }
        return true
    }

    private fun resultColumns(meta: ResultSetMetaData): List<String> {
        return (1..meta.columnCount).map { meta.getColumnLabel(it) }
    }

    private fun rowValues(rs: ResultSet, valueReader: (ResultSet, Int, Int) -> Any?): List<Any?> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { i -> valueReader(rs, i, meta.getColumnType(i)) }
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

private data class QuerySession(
    val id: String,
    val statement: Statement,
    val resultSet: ResultSet,
    val columns: List<String>,
    val maxRows: Int,
    val valueReader: (ResultSet, Int, Int) -> Any?,
    var rowsRead: Int = 0,
    var pendingRow: List<Any?>? = null,
    var lastAccessedAtMillis: Long = System.currentTimeMillis(),
)

object JdbcIdentifiers {
    fun doubleQuote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
    fun backtick(identifier: String): String = "`${identifier.replace("`", "``")}`"
}
