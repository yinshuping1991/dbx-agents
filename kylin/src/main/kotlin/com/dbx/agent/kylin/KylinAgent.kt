package com.dbx.agent.kylin

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class KylinAgent : DatabaseAgent {
    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("org.apache.kylin.jdbc.Driver")
        val url = "jdbc:kylin://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("org.apache.kylin.jdbc.Driver")
        val url = "jdbc:kylin://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<DatabaseInfo>()
        val rs = conn.metaData.catalogs
        rs.use {
            while (it.next()) {
                result.add(DatabaseInfo(it.getString("TABLE_CAT")))
            }
        }
        return result
    }

    override fun listSchemas(): List<String> {
        // Kylin uses project, not schema
        return emptyList()
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<TableInfo>()
        val rs = conn.metaData.getTables(null, schema, null, null)
        rs.use {
            while (it.next()) {
                val tableType = it.getString("TABLE_TYPE").let { t ->
                    if (t == "BASE TABLE") "TABLE" else t
                }
                result.add(TableInfo(it.getString("TABLE_NAME"), tableType))
            }
        }
        return result
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")

        // Get primary key columns
        val primaryKeys = mutableSetOf<String>()
        conn.metaData.getPrimaryKeys(null, schema, table).use { rs ->
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"))
            }
        }

        // Get columns via metadata
        val result = mutableListOf<ColumnInfo>()
        conn.metaData.getColumns(null, schema, table, null).use { rs ->
            while (rs.next()) {
                val colName = rs.getString("COLUMN_NAME")
                result.add(
                    ColumnInfo(
                        name = colName,
                        data_type = rs.getString("TYPE_NAME"),
                        is_nullable = rs.getString("IS_NULLABLE") == "YES",
                        column_default = rs.getString("COLUMN_DEF"),
                        is_primary_key = colName in primaryKeys,
                        extra = null,
                        comment = rs.getString("REMARKS")?.ifEmpty { null },
                        numeric_precision = rs.getObject("COLUMN_SIZE")?.let { (it as Number).toInt() },
                        numeric_scale = rs.getObject("DECIMAL_DIGITS")?.let { (it as Number).toInt() },
                        character_maximum_length = null
                    )
                )
            }
        }
        return result
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // Kylin does not support indexes
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        // Kylin does not support foreign keys
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        // Kylin does not support triggers
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(
            conn = connection ?: throw IllegalStateException("Not connected"),
            sql = sql,
            schema = schema,
            setSchemaSql = ::setSchemaSQL,
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
    JsonRpcServer(KylinAgent()).run()
}
