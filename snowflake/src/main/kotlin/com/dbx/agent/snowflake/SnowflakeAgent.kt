package com.dbx.agent.snowflake

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class SnowflakeAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver")
        val url = "jdbc:snowflake://${params.host}:${params.port}/?db=${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver")
        val url = "jdbc:snowflake://${params.host}:${params.port}/?db=${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString("name")))
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listSchemas(): List<String> {
        val conn = requireConnection()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW SCHEMAS").use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(rs.getString("name"))
                    }
                }.sorted()
            }
        }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        conn.prepareStatement(
            """
            SELECT TABLE_NAME, TABLE_TYPE
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val tableType = rs.getString("TABLE_TYPE").let {
                            if (it == "BASE TABLE") "TABLE" else it
                        }
                        add(TableInfo(rs.getString("TABLE_NAME"), tableType))
                    }
                }
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()

        // Snowflake does not have traditional primary key info in INFORMATION_SCHEMA easily,
        // use SHOW PRIMARY KEYS
        val primaryKeys = mutableSetOf<String>()
        try {
            conn.createStatement().use { stmt ->
                val qualifiedTable = "${JdbcIdentifiers.doubleQuote(schema)}.${JdbcIdentifiers.doubleQuote(table)}"
                stmt.executeQuery("SHOW PRIMARY KEYS IN TABLE $qualifiedTable").use { rs ->
                    while (rs.next()) {
                        primaryKeys.add(rs.getString("column_name"))
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore if SHOW PRIMARY KEYS is not supported or fails
        }

        conn.prepareStatement(
            """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                   NUMERIC_PRECISION, NUMERIC_SCALE, CHARACTER_MAXIMUM_LENGTH, COMMENT
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colName = rs.getString("COLUMN_NAME")
                        add(ColumnInfo(
                            name = colName,
                            data_type = rs.getString("DATA_TYPE"),
                            is_nullable = rs.getString("IS_NULLABLE") == "YES",
                            column_default = rs.getString("COLUMN_DEFAULT"),
                            is_primary_key = colName in primaryKeys,
                            extra = null,
                            comment = rs.getString("COMMENT")?.ifEmpty { null },
                            numeric_precision = rs.getObject("NUMERIC_PRECISION")?.let { (it as Number).toInt() },
                            numeric_scale = rs.getObject("NUMERIC_SCALE")?.let { (it as Number).toInt() },
                            character_maximum_length = rs.getObject("CHARACTER_MAXIMUM_LENGTH")?.let { (it as Number).toInt() }
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // Snowflake does not support traditional indexes
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = requireConnection()
        return try {
            conn.createStatement().use { stmt ->
                val qualifiedTable = "${JdbcIdentifiers.doubleQuote(schema)}.${JdbcIdentifiers.doubleQuote(table)}"
                stmt.executeQuery("SHOW IMPORTED KEYS IN TABLE $qualifiedTable").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(ForeignKeyInfo(
                                name = rs.getString("fk_name"),
                                column = rs.getString("fk_column_name"),
                                ref_table = rs.getString("pk_table_name"),
                                ref_column = rs.getString("pk_column_name")
                            ))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        // Snowflake does not support triggers
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, valueReader = ::stringResultValue)
    }

    override fun setSchemaSQL(schema: String): String = "USE SCHEMA ${JdbcIdentifiers.doubleQuote(schema)}"

    private fun stringResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
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
    JsonRpcServer(SnowflakeAgent()).run()
}
