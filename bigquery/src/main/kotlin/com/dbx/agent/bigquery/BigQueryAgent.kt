package com.dbx.agent.bigquery

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class BigQueryAgent : DatabaseAgent {
    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("com.simba.googlebigquery.jdbc.Driver")
        val url = "jdbc:bigquery://${params.host}:${params.port};ProjectId=${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.simba.googlebigquery.jdbc.Driver")
        val url = "jdbc:bigquery://${params.host}:${params.port};ProjectId=${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<DatabaseInfo>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT schema_name FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY schema_name").use { rs ->
                while (rs.next()) {
                    result.add(DatabaseInfo(rs.getString(1)))
                }
            }
        }
        return result
    }

    override fun listSchemas(): List<String> {
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<TableInfo>()
        val quotedSchema = JdbcIdentifiers.backtick(schema)
        conn.prepareStatement(
            "SELECT table_name, table_type FROM $quotedSchema.INFORMATION_SCHEMA.TABLES ORDER BY table_name"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val tableType = rs.getString("table_type").let {
                        if (it == "BASE TABLE") "TABLE" else it
                    }
                    result.add(TableInfo(rs.getString("table_name"), tableType))
                }
            }
        }
        return result
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<ColumnInfo>()
        val quotedSchema = JdbcIdentifiers.backtick(schema)
        conn.prepareStatement(
            """
            SELECT column_name, data_type, is_nullable
            FROM $quotedSchema.INFORMATION_SCHEMA.COLUMNS
            WHERE table_name = ?
            ORDER BY ordinal_position
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        ColumnInfo(
                            name = rs.getString("column_name"),
                            data_type = rs.getString("data_type"),
                            is_nullable = rs.getString("is_nullable") == "YES",
                            column_default = null,
                            is_primary_key = false,
                            extra = null,
                            comment = null,
                            numeric_precision = null,
                            numeric_scale = null,
                            character_maximum_length = null
                        )
                    )
                }
            }
        }
        return result
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // BigQuery does not support indexes
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        // BigQuery does not support foreign keys
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        // BigQuery does not support triggers
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(
            conn = connection ?: throw IllegalStateException("Not connected"),
            sql = sql,
            schema = schema,
            setSchemaSql = ::setSchemaSQL,
            maxRows = options.maxRows,
            fetchSize = options.fetchSize,
            valueReader = ::getResultValue,
        )
    }

    override fun setSchemaSQL(schema: String): String = ""

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun getResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
        val value = when (sqlType) {
            Types.BIGINT -> rs.getLong(index)
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rs.getInt(index)
            Types.FLOAT, Types.REAL -> rs.getFloat(index)
            Types.DOUBLE -> rs.getDouble(index)
            Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(index)
            Types.BOOLEAN, Types.BIT -> rs.getBoolean(index)
            else -> rs.getString(index)
        }
        return if (rs.wasNull()) null else value
    }
}

fun main() {
    JsonRpcServer(BigQueryAgent()).run()
}
