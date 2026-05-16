package com.dbx.agent.tdengine

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.format.DateTimeFormatter

class TDengineAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver")
        connection = DriverManager.getConnection(TDengineJdbcUrl.from(params), params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver")
        DriverManager.getConnection(TDengineJdbcUrl.from(params), params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        requireConnection().createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
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
        val stableTables = queryTables("SHOW ${quoteQualifiedPrefix(schema)}STABLES", "STABLE")
        val normalTables = queryTables("SHOW ${quoteQualifiedPrefix(schema)}TABLES", "TABLE")
        return (stableTables + normalTables).distinctBy { "${it.table_type}:${it.name}" }.sortedBy { it.name.lowercase() }
    }

    override fun listObjects(schema: String): List<ObjectInfo> {
        return listTables(schema).map { ObjectInfo(name = it.name, object_type = it.table_type, schema = schema, comment = it.comment) }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        requireConnection().createStatement().use { stmt ->
            stmt.executeQuery("DESCRIBE ${qualifiedName(schema, table)}").use { rs ->
                return readDescribeColumns(rs)
            }
        }
    }

    override fun getObjectSource(schema: String, name: String, objectType: String): ObjectSource {
        val source = getCreateSql(schema, name, objectType).ifBlank {
            getTableDdl(schema, name)
        }
        return ObjectSource(name = name, object_type = objectType, schema = schema, source = source)
    }

    override fun getTableDdl(schema: String, table: String): String {
        val source = getCreateSql(schema, table, "STABLE").ifBlank {
            getCreateSql(schema, table, "TABLE")
        }
        return source.ifBlank {
            super.getTableDdl(schema, table)
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, valueReader = ::tdengineResultValue)
    }

    override fun setSchemaSQL(schema: String): String = "USE ${quoteIdentifier(schema)}"

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun queryTables(sql: String, tableType: String): List<TableInfo> {
        requireConnection().createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TableInfo(name = rs.getString(1), table_type = tableType))
                    }
                }
            }
        }
    }

    private fun readDescribeColumns(rs: ResultSet): List<ColumnInfo> {
        return buildList {
            var ordinal = 0
            while (rs.next()) {
                ordinal += 1
                val name = rs.getString(1)
                val dataType = rs.getString(2) ?: ""
                val note = runCatching { rs.getString(4) }.getOrNull()
                val isTag = note?.contains("TAG", ignoreCase = true) == true
                add(ColumnInfo(
                    name = name,
                    data_type = dataType,
                    is_nullable = ordinal != 1,
                    column_default = null,
                    is_primary_key = ordinal == 1 && !isTag,
                    extra = note,
                    comment = if (isTag) "TAG" else null,
                    numeric_precision = parseNumericPrecision(dataType),
                    numeric_scale = parseNumericScale(dataType),
                    character_maximum_length = parseCharacterMaximumLength(dataType),
                ))
            }
        }
    }

    private fun getCreateSql(schema: String, name: String, objectType: String): String {
        val showType = when (objectType.uppercase()) {
            "STABLE", "SUPER TABLE", "SUPERTABLE" -> "STABLE"
            "TABLE", "BASE TABLE", "CHILD TABLE" -> "TABLE"
            else -> return ""
        }
        return runCatching {
            requireConnection().createStatement().use { stmt ->
                stmt.executeQuery("SHOW CREATE $showType ${qualifiedName(schema, name)}").use { rs ->
                    if (!rs.next()) "" else (2..rs.metaData.columnCount)
                        .asSequence()
                        .mapNotNull { rs.getString(it) }
                        .firstOrNull { it.contains("CREATE", ignoreCase = true) }
                        ?: rs.getString(rs.metaData.columnCount).orEmpty()
                }
            }
        }.getOrDefault("")
    }

    private fun tdengineResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
        val value = when (sqlType) {
            Types.BIGINT -> rs.getLong(index)
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rs.getInt(index)
            Types.FLOAT, Types.REAL -> rs.getFloat(index)
            Types.DOUBLE -> rs.getDouble(index)
            Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(index)
            Types.BOOLEAN, Types.BIT -> rs.getBoolean(index)
            else -> rs.getObject(index)
        }
        return if (rs.wasNull()) null else decodeTdengineValue(value)
    }

    private fun quoteQualifiedPrefix(schema: String): String {
        return schema.trim().takeIf { it.isNotEmpty() }?.let { "${quoteIdentifier(it)}." }.orEmpty()
    }

    private fun qualifiedName(schema: String, name: String): String {
        val table = quoteIdentifier(name)
        return schema.trim().takeIf { it.isNotEmpty() }?.let { "${quoteIdentifier(it)}.$table" } ?: table
    }

    private fun quoteIdentifier(identifier: String): String {
        return "`" + identifier.replace("`", "``") + "`"
    }

    private fun parseNumericPrecision(dataType: String): Int? {
        return Regex("(?i)^(decimal|numeric)\\((\\d+)(?:,\\s*\\d+)?\\)").find(dataType)?.groupValues?.get(2)?.toIntOrNull()
    }

    private fun parseNumericScale(dataType: String): Int? {
        return Regex("(?i)^(decimal|numeric)\\(\\d+,\\s*(\\d+)\\)").find(dataType)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseCharacterMaximumLength(dataType: String): Int? {
        return Regex("(?i)^(binary|nchar|varchar|varbinary)\\((\\d+)\\)").find(dataType)?.groupValues?.get(2)?.toIntOrNull()
    }
}

internal fun decodeTdengineValue(value: Any?): Any? {
    return when (value) {
        is ByteArray -> value.toString(Charsets.UTF_8)
        is Timestamp -> TDENGINE_TIMESTAMP_FORMAT.format(value.toLocalDateTime())
        else -> value
    }
}

private val TDENGINE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

object TDengineJdbcUrl {
    fun from(params: ConnectParams): String {
        val host = params.host.ifBlank { "localhost" }
        val port = if (params.port > 0) params.port else 6041
        val database = params.database.trim()
        val path = if (database.isBlank()) "/" else "/$database"
        val query = params.url_params.trim().removePrefix("?")
        val suffix = if (query.isBlank()) "" else "?$query"
        return "jdbc:TAOS-WS://$host:$port$path$suffix"
    }
}

fun main() {
    JsonRpcServer(TDengineAgent()).run()
}
