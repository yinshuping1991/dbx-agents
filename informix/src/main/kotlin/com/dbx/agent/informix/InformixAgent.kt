package com.dbx.agent.informix

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.abs

class InformixAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    companion object {
        fun buildJdbcUrl(params: ConnectParams): String {
            val extraParams = params.url_params
                .trim()
                .trimStart(':', ';')
                .trimEnd(';')
            val serverParam = if (extraParams.contains("INFORMIXSERVER=", ignoreCase = true)) {
                ""
            } else {
                "INFORMIXSERVER=${params.host}"
            }
            val jdbcParams = listOf(serverParam, extraParams)
                .filter { it.isNotBlank() }
                .joinToString(";")
            return "jdbc:informix-sqli://${params.host}:${params.port}/${params.database}:$jdbcParams"
        }

        /**
         * Map Informix coltype integer codes to type names.
         * See Informix documentation for coltype values.
         */
        fun mapColType(coltype: Int): String {
            val baseType = coltype % 256
            return when (baseType) {
                0 -> "CHAR"
                1 -> "SMALLINT"
                2 -> "INTEGER"
                3 -> "FLOAT"
                4 -> "SMALLFLOAT"
                5 -> "DECIMAL"
                6 -> "SERIAL"
                7 -> "DATE"
                8 -> "MONEY"
                9 -> "NULL"
                10 -> "DATETIME"
                11 -> "BYTE"
                12 -> "TEXT"
                13 -> "VARCHAR"
                14 -> "INTERVAL"
                15 -> "NCHAR"
                16 -> "NVARCHAR"
                17 -> "INT8"
                18 -> "SERIAL8"
                19 -> "SET"
                20 -> "MULTISET"
                21 -> "LIST"
                22 -> "ROW"
                23 -> "COLLECTION"
                40 -> "LVARCHAR"
                41 -> "BOOLEAN"
                43 -> "BIGINT"
                44 -> "BIGSERIAL"
                52 -> "BIGINT"
                53 -> "BIGSERIAL"
                else -> "UNKNOWN($baseType)"
            }
        }

        fun primaryKeyColumnNumbers(parts: List<Int?>): Set<Int> {
            return parts
                .mapNotNull { part ->
                    part?.let { abs(it) }?.takeIf { it > 0 }
                }
                .toSet()
        }

        fun databaseCatalogSql(): String = "SELECT name FROM sysmaster:sysdatabases ORDER BY name"
    }

    override fun connect(params: ConnectParams) {
        Class.forName("com.informix.jdbc.IfxDriver")
        val url = buildJdbcUrl(params)
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.informix.jdbc.IfxDriver")
        val url = buildJdbcUrl(params)
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val sql = databaseCatalogSql()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString(1).trim()))
                    }
                }
            }
        }
    }

    override fun listSchemas(): List<String> {
        // Informix schemas ≈ databases
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT tabname,
                CASE tabtype WHEN 'T' THEN 'TABLE' WHEN 'V' THEN 'VIEW' ELSE tabtype END
            FROM systables
            WHERE tabid >= 100
            ORDER BY tabname
        """.trimIndent()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TableInfo(
                            name = rs.getString(1).trim(),
                            table_type = rs.getString(2).trim()
                        ))
                    }
                }
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()
        val primaryKeyColumns = getPrimaryKeyColumnNumbers(conn, table)
        val sql = """
            SELECT c.colname, c.coltype, c.colno
            FROM syscolumns c
            WHERE c.tabid = (SELECT tabid FROM systables WHERE tabname = ?)
            ORDER BY c.colno
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colname = rs.getString(1).trim()
                        val coltype = rs.getInt(2)
                        val typeName = mapColType(coltype)
                        // Bit 8 (256) indicates NOT NULL
                        val isNullable = (coltype and 256) == 0

                        add(ColumnInfo(
                            name = colname,
                            data_type = typeName,
                            is_nullable = isNullable,
                            column_default = null,
                            is_primary_key = primaryKeyColumns.contains(rs.getInt(3)),
                            extra = null,
                            comment = null
                        ))
                    }
                }
            }
        }
    }

    private fun getPrimaryKeyColumnNumbers(conn: Connection, table: String): Set<Int> {
        val sql = """
            SELECT i.part1, i.part2, i.part3, i.part4, i.part5, i.part6, i.part7, i.part8,
                   i.part9, i.part10, i.part11, i.part12, i.part13, i.part14, i.part15, i.part16
            FROM sysconstraints c
            JOIN sysindexes i ON i.idxname = c.idxname AND i.tabid = c.tabid
            JOIN systables t ON t.tabid = c.tabid
            WHERE t.tabname = ? AND c.constrtype = 'P'
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return emptySet()
                return primaryKeyColumnNumbers((1..16).map { index ->
                    val value = rs.getInt(index)
                    if (rs.wasNull()) null else value
                })
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // Complex in Informix — return empty
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT t.trigname, t.event, 'TRIGGER'
            FROM systriggers t
            JOIN systables s ON t.tabid = s.tabid
            WHERE s.tabname = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TriggerInfo(
                            name = rs.getString(1).trim(),
                            event = rs.getString(2).trim(),
                            timing = rs.getString(3).trim()
                        ))
                    }
                }
            }
        }
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        val normalizedSql = when (sql.trim().trimEnd(';').uppercase()) {
            "BEGIN WORK" -> "BEGIN"
            "COMMIT WORK" -> "COMMIT"
            "ROLLBACK WORK" -> "ROLLBACK"
            else -> sql
        }
        return JdbcExecutor.execute(requireConnection(), normalizedSql, schema, ::setSchemaSQL, valueReader = ::stringResultValue)
    }

    override fun setSchemaSQL(schema: String): String = ""

    private fun stringResultValue(rs: java.sql.ResultSet, index: Int, sqlType: Int): Any? {
        val value = rs.getObject(index)
        return if (rs.wasNull()) null else value?.toString()
    }

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }
}

fun main() {
    JsonRpcServer(InformixAgent()).run()
}
