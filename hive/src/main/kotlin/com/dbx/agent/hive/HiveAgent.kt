package com.dbx.agent.hive

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager

class HiveAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName("org.apache.hive.jdbc.HiveDriver")
        val url = "jdbc:hive2://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("org.apache.hive.jdbc.HiveDriver")
        val url = "jdbc:hive2://${params.host}:${params.port}/${params.database}"
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
                        add(DatabaseInfo(rs.getString(1)))
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listSchemas(): List<String> {
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        // Switch to the target database first
        conn.createStatement().use { stmt ->
            stmt.execute("USE ${JdbcIdentifiers.backtick(schema)}")
        }
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW TABLES").use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TableInfo(
                            name = rs.getString(1),
                            table_type = "TABLE"
                        ))
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()
        // Switch to the target database first
        conn.createStatement().use { stmt ->
            stmt.execute("USE ${JdbcIdentifiers.backtick(schema)}")
        }
        conn.createStatement().use { stmt ->
            stmt.executeQuery("DESCRIBE ${JdbcIdentifiers.backtick(table)}").use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colName = rs.getString(1)?.trim() ?: continue
                        // DESCRIBE output may include partition info headers (empty lines or "# col_name")
                        if (colName.isEmpty() || colName.startsWith("#")) continue
                        val dataType = rs.getString(2)?.trim() ?: ""
                        val comment = try { rs.getString(3)?.trim()?.ifEmpty { null } } catch (_: Exception) { null }

                        add(ColumnInfo(
                            name = colName,
                            data_type = dataType,
                            is_nullable = true, // Hive columns are nullable by default
                            column_default = null,
                            is_primary_key = false, // Hive does not have traditional primary keys
                            extra = null,
                            comment = comment,
                            numeric_precision = null,
                            numeric_scale = null,
                            character_maximum_length = null
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        // Hive does not have traditional indexes
        return emptyList()
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        // Hive does not have foreign keys
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        // Hive does not have triggers
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, maxRows = options.maxRows, fetchSize = options.fetchSize, valueReader = ::stringResultValue)
    }

    override fun setSchemaSQL(schema: String): String = "USE ${JdbcIdentifiers.backtick(schema)}"

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun stringResultValue(rs: java.sql.ResultSet, index: Int, sqlType: Int): Any? {
        val value = rs.getObject(index)
        return if (rs.wasNull()) null else value?.toString()
    }
}

fun main() {
    JsonRpcServer(HiveAgent()).run()
}
