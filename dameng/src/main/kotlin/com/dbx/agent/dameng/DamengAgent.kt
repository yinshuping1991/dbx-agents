package com.dbx.agent.dameng

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class DamengAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection
    companion object {
        private const val MAX_ROWS = 10000

        private val SYSTEM_USERS = setOf(
            "SYS", "SYSAUDITOR", "SYSSSO", "CTISYS",
            "SYS_DBA", "_SYS_STATISTICS", "SYS_PHM"
        )

        private val QUERY_PREFIXES = listOf("SELECT", "WITH", "SHOW", "DESCRIBE", "EXPLAIN")
    }

    override fun connect(params: ConnectParams) {
        Class.forName("dm.jdbc.driver.DmDriver")
        val url = "jdbc:dm://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("dm.jdbc.driver.DmDriver")
        val url = "jdbc:dm://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val placeholders = SYSTEM_USERS.joinToString(",") { "?" }
        val sql = "SELECT USERNAME FROM ALL_USERS WHERE USERNAME NOT IN ($placeholders) ORDER BY USERNAME"
        conn.prepareStatement(sql).use { stmt ->
            SYSTEM_USERS.forEachIndexed { index, name ->
                stmt.setString(index + 1, name)
            }
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString(1)))
                    }
                }
            }
        }
    }

    override fun listSchemas(): List<String> {
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT TABLE_NAME, 'TABLE' AS TABLE_TYPE FROM ALL_TABLES WHERE OWNER = ?
            UNION ALL
            SELECT VIEW_NAME, 'VIEW' FROM ALL_VIEWS WHERE OWNER = ?
            ORDER BY 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, schema)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TableInfo(
                            name = rs.getString(1),
                            table_type = rs.getString(2)
                        ))
                    }
                }
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()

        // Fetch primary key columns
        val pkColumns = mutableSetOf<String>()
        val pkSql = """
            SELECT cols.COLUMN_NAME FROM ALL_CONS_COLUMNS cols
            JOIN ALL_CONSTRAINTS cons ON cols.CONSTRAINT_NAME = cons.CONSTRAINT_NAME AND cols.OWNER = cons.OWNER
            WHERE cons.CONSTRAINT_TYPE = 'P' AND cons.OWNER = ? AND cons.TABLE_NAME = ?
        """.trimIndent()
        conn.prepareStatement(pkSql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    pkColumns.add(rs.getString(1))
                }
            }
        }

        // Fetch column metadata
        val colSql = """
            SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, DATA_PRECISION, DATA_SCALE, DATA_LENGTH, CHAR_LENGTH
            FROM ALL_TAB_COLUMNS
            WHERE OWNER = ? AND TABLE_NAME = ?
            ORDER BY COLUMN_ID
        """.trimIndent()
        conn.prepareStatement(colSql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val name = rs.getString("COLUMN_NAME")
                        val baseType = rs.getString("DATA_TYPE")
                        val numPrec = rs.getObject("DATA_PRECISION")?.let { (it as Number).toInt() }
                        val numScale = rs.getObject("DATA_SCALE")?.let { (it as Number).toInt() }
                        val dataLen = rs.getObject("DATA_LENGTH")?.let { (it as Number).toInt() }
                        val charLen = rs.getObject("CHAR_LENGTH")?.let { (it as Number).toInt() }
                        val dataType = formatDataType(baseType, numPrec, numScale, dataLen, charLen)

                        add(ColumnInfo(
                            name = name,
                            data_type = dataType,
                            is_nullable = rs.getString("NULLABLE") == "Y",
                            column_default = null,
                            is_primary_key = pkColumns.contains(name),
                            extra = null,
                            comment = null,
                            numeric_precision = numPrec,
                            numeric_scale = numScale,
                            character_maximum_length = charLen
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT i.INDEX_NAME,
                LISTAGG(ic.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY ic.COLUMN_POSITION) AS COLUMNS,
                i.UNIQUENESS,
                CASE WHEN c.CONSTRAINT_TYPE = 'P' THEN 1 ELSE 0 END AS IS_PK,
                i.INDEX_TYPE
            FROM ALL_INDEXES i
            JOIN ALL_IND_COLUMNS ic ON i.INDEX_NAME = ic.INDEX_NAME AND i.OWNER = ic.INDEX_OWNER AND i.TABLE_OWNER = ic.TABLE_OWNER
            LEFT JOIN ALL_CONSTRAINTS c ON i.INDEX_NAME = c.INDEX_NAME AND i.TABLE_OWNER = c.OWNER
                AND c.CONSTRAINT_TYPE = 'P'
            WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ?
            GROUP BY i.INDEX_NAME, i.UNIQUENESS, c.CONSTRAINT_TYPE, i.INDEX_TYPE
            ORDER BY i.INDEX_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colsStr = rs.getString(2) ?: ""
                        add(IndexInfo(
                            name = rs.getString(1),
                            columns = colsStr.split(",").filter { it.isNotEmpty() },
                            is_unique = rs.getString(3) == "UNIQUE",
                            is_primary = rs.getString(4) == "1",
                            filter = null,
                            index_type = rs.getString(5)
                        ))
                    }
                }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT c.CONSTRAINT_NAME, cc.COLUMN_NAME, rc.TABLE_NAME, rcc.COLUMN_NAME
            FROM ALL_CONSTRAINTS c
            JOIN ALL_CONS_COLUMNS cc ON c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME AND c.OWNER = cc.OWNER
            JOIN ALL_CONSTRAINTS rc ON c.R_CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND c.R_OWNER = rc.OWNER
            JOIN ALL_CONS_COLUMNS rcc ON rc.CONSTRAINT_NAME = rcc.CONSTRAINT_NAME AND rc.OWNER = rcc.OWNER
            WHERE c.CONSTRAINT_TYPE = 'R' AND c.OWNER = ? AND c.TABLE_NAME = ?
            ORDER BY c.CONSTRAINT_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(ForeignKeyInfo(
                            name = rs.getString(1),
                            column = rs.getString(2),
                            ref_table = rs.getString(3),
                            ref_column = rs.getString(4)
                        ))
                    }
                }
            }
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT TRIGGER_NAME, TRIGGERING_EVENT, TRIGGER_TYPE
            FROM ALL_TRIGGERS
            WHERE OWNER = ? AND TABLE_NAME = ?
            ORDER BY TRIGGER_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TriggerInfo(
                            name = rs.getString(1),
                            event = rs.getString(2),
                            timing = rs.getString(3)
                        ))
                    }
                }
            }
        }
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        val conn = requireConnection()
        val trimmedSql = sql.trim().trimEnd(';')
        val upperSql = trimmedSql.uppercase().trimStart()

        // Translate transaction control to JDBC calls
        if (upperSql == "BEGIN" || upperSql == "BEGIN TRANSACTION") {
            val start = System.currentTimeMillis()
            conn.autoCommit = false
            return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
        }
        if (upperSql == "COMMIT") {
            val start = System.currentTimeMillis()
            conn.commit()
            conn.autoCommit = true
            return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
        }
        if (upperSql == "ROLLBACK") {
            val start = System.currentTimeMillis()
            conn.rollback()
            conn.autoCommit = true
            return QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start)
        }

        // Set schema if provided
        if (!schema.isNullOrBlank()) {
            conn.createStatement().use { stmt ->
                stmt.execute("SET SCHEMA \"$schema\"")
            }
        }

        val startTime = System.currentTimeMillis()
        val isQuery = QUERY_PREFIXES.any { upperSql.startsWith(it) }

        if (isQuery) {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(trimmedSql).use { rs ->
                    val meta = rs.metaData
                    val colCount = meta.columnCount
                    val columns = (1..colCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<List<Any?>>()
                    while (rs.next() && rows.size < MAX_ROWS) {
                        val row = (1..colCount).map { i ->
                            val value = rs.getObject(i)
                            if (rs.wasNull()) null else value?.toString()
                        }
                        rows.add(row)
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    return QueryResult(
                        columns = columns,
                        rows = rows,
                        affected_rows = 0,
                        execution_time_ms = elapsed,
                        truncated = rows.size >= MAX_ROWS
                    )
                }
            }
        } else {
            conn.createStatement().use { stmt ->
                val affected = stmt.executeUpdate(trimmedSql)
                val elapsed = System.currentTimeMillis() - startTime
                return QueryResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    affected_rows = affected.toLong(),
                    execution_time_ms = elapsed,
                    truncated = false
                )
            }
        }
    }

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun formatDataType(
        base: String,
        numPrec: Int?,
        numScale: Int?,
        dataLen: Int?,
        charLen: Int?
    ): String {
        return when (base.uppercase()) {
            "VARCHAR2", "NVARCHAR2", "VARCHAR", "CHAR", "NCHAR" -> {
                val len = charLen ?: dataLen
                if (len != null) "$base($len)" else base
            }
            "NUMBER", "NUMERIC", "DECIMAL" -> {
                when {
                    numPrec != null && numScale != null && numScale > 0 -> "$base($numPrec,$numScale)"
                    numPrec != null && numPrec > 0 -> "$base($numPrec)"
                    else -> base
                }
            }
            "RAW" -> {
                if (dataLen != null) "RAW($dataLen)" else "RAW"
            }
            else -> base
        }
    }
}

fun main() {
    JsonRpcServer(DamengAgent()).run()
}
