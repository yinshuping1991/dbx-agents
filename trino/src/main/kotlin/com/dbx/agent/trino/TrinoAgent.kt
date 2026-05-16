package com.dbx.agent.trino

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.Types

class TrinoAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("io.trino.jdbc.TrinoDriver")
        val url = "jdbc:trino://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("io.trino.jdbc.TrinoDriver")
        val url = "jdbc:trino://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW CATALOGS").use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString(1)))
                    }
                }
            }
        }
    }

    override fun listSchemas(): List<String> {
        val conn = requireConnection()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW SCHEMAS").use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(rs.getString(1))
                    }
                }
            }
        }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        conn.prepareStatement(
            """
            SELECT table_name, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val tableType = rs.getString("table_type").let {
                            if (it == "BASE TABLE") "TABLE" else it
                        }
                        add(TableInfo(rs.getString("table_name"), tableType))
                    }
                }
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()
        val metadataColumns = try {
            getColumnsFromMetadata(conn, schema, table)
        } catch (_: Exception) {
            emptyList()
        }
        return metadataColumns.ifEmpty { getColumnsFromDescribe(conn, schema, table) }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // Trino does not support traditional indexes
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        // Trino does not expose foreign key metadata
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        // Trino does not support triggers
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, maxRows = options.maxRows, fetchSize = options.fetchSize, valueReader = ::stringResultValue)
    }

    override fun setSchemaSQL(schema: String): String = "USE ${JdbcIdentifiers.doubleQuote(schema)}"

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun getColumnsFromMetadata(conn: Connection, schema: String, table: String): List<ColumnInfo> {
        val catalog = conn.catalog?.takeIf { it.isNotBlank() }
        conn.metaData.getColumns(catalog, schema, table, null).use { rs ->
            return buildList {
                while (rs.next()) {
                    val sqlType = rs.getInt("DATA_TYPE")
                    val size = rs.getObject("COLUMN_SIZE")?.let { (it as Number).toInt() }
                    val scale = rs.getObject("DECIMAL_DIGITS")?.let { (it as Number).toInt() }
                    add(ColumnInfo(
                        name = rs.getString("COLUMN_NAME"),
                        data_type = rs.getString("TYPE_NAME") ?: "",
                        is_nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        column_default = rs.getString("COLUMN_DEF"),
                        is_primary_key = false, // Trino does not expose PK info in JDBC metadata
                        extra = null,
                        comment = rs.getString("REMARKS"),
                        numeric_precision = if (isNumericType(sqlType)) size else null,
                        numeric_scale = if (isNumericType(sqlType)) scale else null,
                        character_maximum_length = if (isCharacterType(sqlType)) size else null
                    ))
                }
            }
        }
    }

    private fun stringResultValue(rs: java.sql.ResultSet, index: Int, sqlType: Int): Any? {
        val value = rs.getObject(index)
        return if (rs.wasNull()) null else value?.toString()
    }

    private fun getColumnsFromDescribe(conn: Connection, schema: String, table: String): List<ColumnInfo> {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("DESCRIBE ${quoteIdentifier(schema)}.${quoteIdentifier(table)}").use { rs ->
                return buildList {
                    while (rs.next()) {
                        val dataType = rs.getString(2)
                        val extra = rs.getString(3)
                        add(ColumnInfo(
                            name = rs.getString(1),
                            data_type = dataType,
                            is_nullable = !extra.orEmpty().contains("not null", ignoreCase = true),
                            column_default = null,
                            is_primary_key = false,
                            extra = extra,
                            comment = rs.getString(4),
                            numeric_precision = parseNumericPrecision(dataType),
                            numeric_scale = parseNumericScale(dataType),
                            character_maximum_length = parseCharacterMaximumLength(dataType)
                        ))
                    }
                }
            }
        }
    }

    private fun quoteIdentifier(identifier: String): String {
        return "\"${identifier.replace("\"", "\"\"")}\""
    }

    private fun isNumericType(sqlType: Int): Boolean {
        return when (sqlType) {
            Types.BIGINT,
            Types.DECIMAL,
            Types.DOUBLE,
            Types.FLOAT,
            Types.INTEGER,
            Types.NUMERIC,
            Types.REAL,
            Types.SMALLINT,
            Types.TINYINT -> true
            else -> false
        }
    }

    private fun isCharacterType(sqlType: Int): Boolean {
        return when (sqlType) {
            Types.CHAR,
            Types.LONGNVARCHAR,
            Types.LONGVARCHAR,
            Types.NCHAR,
            Types.NVARCHAR,
            Types.VARCHAR -> true
            else -> false
        }
    }

    private fun parseNumericPrecision(dataType: String): Int? {
        return Regex("(?i)^(decimal|numeric)\\((\\d+)(?:,\\s*\\d+)?\\)").find(dataType)?.groupValues?.get(2)?.toIntOrNull()
    }

    private fun parseNumericScale(dataType: String): Int? {
        return Regex("(?i)^(decimal|numeric)\\(\\d+,\\s*(\\d+)\\)").find(dataType)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseCharacterMaximumLength(dataType: String): Int? {
        return Regex("(?i)^(char|varchar)\\((\\d+)\\)").find(dataType)?.groupValues?.get(2)?.toIntOrNull()
    }
}

fun main() {
    JsonRpcServer(TrinoAgent()).run()
}
