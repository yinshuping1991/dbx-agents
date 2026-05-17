package com.dbx.agent.access

import com.dbx.agent.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager

class AccessAgent : DatabaseAgent {
    private var connection: Connection? = null
    private var databaseFile: String = ""

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("net.ucanaccess.jdbc.UcanaccessDriver")
        val file = databasePath(params)
        val url = jdbcUrl(params, createIfMissing = true)
        connection = DriverManager.getConnection(url, params.username, params.password)
        databaseFile = file
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("net.ucanaccess.jdbc.UcanaccessDriver")
        val file = databasePath(params)
        if (file.isBlank() || !Files.exists(Path.of(file))) return false
        DriverManager.getConnection(jdbcUrl(params, createIfMissing = false), params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val name = Path.of(databaseFile).fileName?.toString()?.takeIf { it.isNotBlank() } ?: "default"
        return listOf(DatabaseInfo(name))
    }

    override fun listSchemas(): List<String> = emptyList()

    override fun listTables(schema: String): List<TableInfo> {
        val meta = requireConnection().metaData
        meta.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            return buildList {
                while (rs.next()) {
                    val name = rs.getString("TABLE_NAME") ?: continue
                    if (name.startsWith("MSys", ignoreCase = true)) continue
                    add(TableInfo(name = name, table_type = normalizeTableType(rs.getString("TABLE_TYPE"))))
                }
            }.sortedBy { it.name.lowercase() }
        }
    }

    override fun listObjects(schema: String): List<ObjectInfo> {
        return listTables(schema).map { ObjectInfo(name = it.name, object_type = it.table_type, schema = null, comment = it.comment) }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val meta = requireConnection().metaData
        val primaryKeys = primaryKeyColumns(meta, table)
        meta.getColumns(null, null, table, "%").use { rs ->
            return buildList {
                while (rs.next()) {
                    val name = rs.getString("COLUMN_NAME")
                    add(
                        ColumnInfo(
                            name = name,
                            data_type = rs.getString("TYPE_NAME") ?: rs.getInt("DATA_TYPE").toString(),
                            is_nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            column_default = rs.getString("COLUMN_DEF"),
                            is_primary_key = name in primaryKeys,
                            extra = null,
                            comment = rs.getString("REMARKS"),
                            numeric_precision = rs.getObject("COLUMN_SIZE")?.let { (it as Number).toInt() },
                            numeric_scale = rs.getObject("DECIMAL_DIGITS")?.let { (it as Number).toInt() },
                            character_maximum_length = rs.getObject("COLUMN_SIZE")?.let { (it as Number).toInt() },
                        )
                    )
                }
            }.sortedBy { columnOrder(meta, table, it.name) }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val meta = requireConnection().metaData
        val primaryKeys = primaryKeyColumns(meta, table)
        meta.getIndexInfo(null, null, table, false, false).use { rs ->
            val columnsByIndex = linkedMapOf<String, MutableList<Pair<Short, String>>>()
            val uniqueByIndex = mutableMapOf<String, Boolean>()
            val primaryByIndex = mutableMapOf<String, Boolean>()
            val typeByIndex = mutableMapOf<String, String>()

            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue
                val indexName = rs.getString("INDEX_NAME") ?: continue
                val columnName = rs.getString("COLUMN_NAME") ?: continue
                val ordinal = runCatching { rs.getShort("ORDINAL_POSITION") }.getOrDefault(0)

                columnsByIndex.getOrPut(indexName) { mutableListOf() }.add(ordinal to columnName)
                uniqueByIndex[indexName] = !rs.getBoolean("NON_UNIQUE")
                primaryByIndex[indexName] = columnName in primaryKeys || indexName.equals("PrimaryKey", ignoreCase = true)
                typeByIndex[indexName] = indexTypeName(rs.getShort("TYPE"))
            }

            return columnsByIndex.map { (name, pairs) ->
                IndexInfo(
                    name = name,
                    columns = pairs.sortedBy { it.first }.map { it.second },
                    is_unique = uniqueByIndex[name] ?: false,
                    is_primary = primaryByIndex[name] ?: false,
                    index_type = typeByIndex[name],
                )
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val meta = requireConnection().metaData
        meta.getImportedKeys(null, null, table).use { rs ->
            return buildList {
                while (rs.next()) {
                    add(
                        ForeignKeyInfo(
                            name = rs.getString("FK_NAME") ?: "",
                            column = rs.getString("FKCOLUMN_NAME"),
                            ref_table = rs.getString("PKTABLE_NAME"),
                            ref_column = rs.getString("PKCOLUMN_NAME"),
                        )
                    )
                }
            }
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> = emptyList()

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, maxRows = options.maxRows, fetchSize = options.fetchSize)
    }

    override fun setSchemaSQL(schema: String): String = ""

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun primaryKeyColumns(meta: DatabaseMetaData, table: String): Set<String> {
        meta.getPrimaryKeys(null, null, table).use { rs ->
            return buildSet {
                while (rs.next()) {
                    add(rs.getString("COLUMN_NAME"))
                }
            }
        }
    }

    private fun columnOrder(meta: DatabaseMetaData, table: String, column: String): Int {
        meta.getColumns(null, null, table, column).use { rs ->
            return if (rs.next()) rs.getInt("ORDINAL_POSITION") else Int.MAX_VALUE
        }
    }

    private fun databasePath(params: ConnectParams): String {
        return when {
            params.connection_string.startsWith("jdbc:ucanaccess:", ignoreCase = true) -> {
                val pathPart = params.connection_string.removePrefix("jdbc:ucanaccess://").substringBefore(';')
                pathPart
            }
            params.database.startsWith("jdbc:ucanaccess:", ignoreCase = true) -> {
                params.database.removePrefix("jdbc:ucanaccess://").substringBefore(';')
            }
            params.host.isNotBlank() -> params.host
            else -> params.database
        }
    }

    private fun jdbcUrl(params: ConnectParams, createIfMissing: Boolean): String {
        val rawUrl = when {
            params.connection_string.startsWith("jdbc:ucanaccess:", ignoreCase = true) -> params.connection_string
            params.database.startsWith("jdbc:ucanaccess:", ignoreCase = true) -> params.database
            else -> "jdbc:ucanaccess://${databasePath(params)}"
        }
        if (!createIfMissing || Files.exists(Path.of(databasePath(params)))) return rawUrl
        if (rawUrl.contains("newDatabaseVersion=", ignoreCase = true)) return rawUrl
        return "$rawUrl;newDatabaseVersion=V2010"
    }

    private fun normalizeTableType(value: String?): String {
        return when (value?.uppercase()) {
            "BASE TABLE" -> "TABLE"
            else -> value ?: "TABLE"
        }
    }

    private fun indexTypeName(value: Short): String {
        return when (value) {
            DatabaseMetaData.tableIndexClustered -> "CLUSTERED"
            DatabaseMetaData.tableIndexHashed -> "HASHED"
            DatabaseMetaData.tableIndexOther -> "OTHER"
            else -> ""
        }
    }
}

fun main() {
    JsonRpcServer(AccessAgent()).run()
}
