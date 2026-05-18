package com.dbx.agent

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

data class JdbcAgentProfile(
    val driverClass: String,
    val urlTemplate: String,
    val defaultPort: Int = 0,
    val skipExecutionContext: Boolean = false,
    val excludedSchemas: Set<String> = emptySet(),
    val tableTypes: List<String> = listOf("TABLE", "VIEW", "MATERIALIZED VIEW", "SYSTEM TABLE", "SYSTEM VIEW"),
) {
    fun buildUrl(params: ConnectParams): String {
        if (params.connection_string.isNotBlank()) {
            return params.connection_string
        }
        val port = if (params.port > 0) params.port else defaultPort
        val base = urlTemplate
            .replace("{host}", params.host)
            .replace("{port}", port.toString())
            .replace("{database}", params.database)
        return appendUrlParams(base, params.url_params)
    }
}

private fun appendUrlParams(url: String, urlParams: String): String {
    val params = urlParams.trim().trimStart('?', '&')
    if (params.isBlank()) return url
    val separator = if (url.contains("?")) "&" else "?"
    return "$url$separator$params"
}

abstract class ConfiguredJdbcAgent(
    open val profile: JdbcAgentProfile,
) : DatabaseAgent {
    private var connection: Connection? = null
    private var configuredDatabase: String = ""

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName(profile.driverClass)
        connection = DriverManager.getConnection(profile.buildUrl(params), params.username, params.password)
        configuredDatabase = params.database
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName(profile.driverClass)
        DriverManager.getConnection(profile.buildUrl(params), params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val names = linkedSetOf<String>()
        runCatching {
            conn.metaData.catalogs.use { rs ->
                while (rs.next()) {
                    rs.getString("TABLE_CAT")?.takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
        }
        configuredDatabase.takeIf { it.isNotBlank() }?.let(names::add)
        conn.catalog?.takeIf { it.isNotBlank() }?.let(names::add)
        return names.map(::DatabaseInfo)
    }

    override fun listSchemas(): List<String> {
        val conn = requireConnection()
        val names = linkedSetOf<String>()
        val meta = conn.metaData
        runCatching {
            meta.getSchemas(null, null).use { rs ->
                while (rs.next()) {
                    rs.getString("TABLE_SCHEM")?.takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
        }.recoverCatching {
            meta.schemas.use { rs ->
                while (rs.next()) {
                    rs.getString("TABLE_SCHEM")?.takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
        }
        conn.schema?.takeIf { it.isNotBlank() }?.let(names::add)
        return names
            .filterNot { it.uppercase() in profile.excludedSchemas }
            .sorted()
    }

    override fun listTables(schema: String): List<TableInfo> {
        val meta = requireConnection().metaData
        val result = mutableListOf<TableInfo>()
        appendTables(result, meta, null, schema)
        if (result.isEmpty() && configuredDatabase.isNotBlank()) {
            appendTables(result, meta, configuredDatabase, schema)
        }
        return result.sortedBy { it.name }
    }

    private fun appendTables(result: MutableList<TableInfo>, meta: DatabaseMetaData, catalog: String?, schema: String) {
        meta.getTables(catalog, schema.ifBlank { null }, "%", profile.tableTypes.toTypedArray()).use { rs ->
            while (rs.next()) {
                result.add(
                    TableInfo(
                        name = rs.getString("TABLE_NAME"),
                        table_type = normalizeTableType(rs.getString("TABLE_TYPE")),
                        comment = rs.getString("REMARKS"),
                    )
                )
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val meta = requireConnection().metaData
        val primaryKeys = primaryKeys(meta, null, schema, table)
        val result = mutableListOf<ColumnInfo>()
        appendColumns(result, meta, null, schema, table, primaryKeys)
        if (result.isEmpty() && configuredDatabase.isNotBlank()) {
            val fallbackPrimaryKeys = primaryKeys(meta, configuredDatabase, schema, table)
            appendColumns(result, meta, configuredDatabase, schema, table, fallbackPrimaryKeys)
        }
        return result
    }

    private fun primaryKeys(meta: DatabaseMetaData, catalog: String?, schema: String, table: String): Set<String> {
        val keys = mutableSetOf<String>()
        runCatching {
            meta.getPrimaryKeys(catalog, schema.ifBlank { null }, table).use { rs ->
                while (rs.next()) {
                    rs.getString("COLUMN_NAME")?.let(keys::add)
                }
            }
        }
        return keys
    }

    private fun appendColumns(
        result: MutableList<ColumnInfo>,
        meta: DatabaseMetaData,
        catalog: String?,
        schema: String,
        table: String,
        primaryKeys: Set<String>,
    ) {
        meta.getColumns(catalog, schema.ifBlank { null }, table, "%").use { rs ->
            while (rs.next()) {
                val name = rs.getString("COLUMN_NAME")
                result.add(
                    ColumnInfo(
                        name = name,
                        data_type = rs.getString("TYPE_NAME"),
                        is_nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        column_default = rs.getString("COLUMN_DEF"),
                        is_primary_key = name in primaryKeys,
                        comment = rs.getString("REMARKS"),
                        numeric_precision = intOrNull(rs, "COLUMN_SIZE"),
                        numeric_scale = intOrNull(rs, "DECIMAL_DIGITS"),
                        character_maximum_length = characterLength(rs),
                    )
                )
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val meta = requireConnection().metaData
        val indexes = linkedMapOf<String, MutableList<Pair<Short, String>>>()
        val unique = mutableMapOf<String, Boolean>()
        meta.getIndexInfo(null, schema.ifBlank { null }, table, false, false).use { rs ->
            while (rs.next()) {
                val name = rs.getString("INDEX_NAME") ?: continue
                val column = rs.getString("COLUMN_NAME") ?: continue
                indexes.getOrPut(name) { mutableListOf() }.add(rs.getShort("ORDINAL_POSITION") to column)
                unique[name] = !rs.getBoolean("NON_UNIQUE")
            }
        }
        return indexes.map { (name, columns) ->
            IndexInfo(
                name = name,
                columns = columns.sortedBy { it.first }.map { it.second },
                is_unique = unique[name] ?: false,
                is_primary = name.equals("PRIMARY", ignoreCase = true),
            )
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val meta = requireConnection().metaData
        val result = mutableListOf<ForeignKeyInfo>()
        meta.getImportedKeys(null, schema.ifBlank { null }, table).use { rs ->
            while (rs.next()) {
                result.add(
                    ForeignKeyInfo(
                        name = rs.getString("FK_NAME") ?: "",
                        column = rs.getString("FKCOLUMN_NAME"),
                        ref_table = rs.getString("PKTABLE_NAME"),
                        ref_column = rs.getString("PKCOLUMN_NAME"),
                    )
                )
            }
        }
        return result
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> = emptyList()

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(
            requireConnection(),
            sql,
            schema,
            ::setSchemaSQL,
            maxRows = options.maxRows,
            fetchSize = options.fetchSize,
            valueReader = ::resultValue,
        )
    }

    override fun setSchemaSQL(schema: String): String {
        if (profile.skipExecutionContext) return ""
        return "SET SCHEMA ${JdbcIdentifiers.doubleQuote(schema)}"
    }

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    protected fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    protected open fun resultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
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

private fun normalizeTableType(type: String?): String {
    return when (type?.uppercase()) {
        "BASE TABLE" -> "TABLE"
        null, "" -> "TABLE"
        else -> type
    }
}

private fun intOrNull(rs: ResultSet, column: String): Int? {
    val value = rs.getObject(column) ?: return null
    return (value as Number).toInt()
}

private fun characterLength(rs: ResultSet): Int? {
    val typeName = rs.getString("TYPE_NAME")?.lowercase() ?: return null
    if (!typeName.contains("char") && !typeName.contains("text")) return null
    return intOrNull(rs, "COLUMN_SIZE")
}
