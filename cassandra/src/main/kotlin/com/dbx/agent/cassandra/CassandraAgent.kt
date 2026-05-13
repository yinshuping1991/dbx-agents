package com.dbx.agent.cassandra

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager

class CassandraAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun setSchemaSQL(schema: String): String = "USE ${JdbcIdentifiers.doubleQuote(schema)}"

    override fun connect(params: ConnectParams) {
        Class.forName("com.ing.data.cassandra.jdbc.CassandraDriver")
        val url = "jdbc:cassandra://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.ing.data.cassandra.jdbc.CassandraDriver")
        val url = "jdbc:cassandra://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val sql = "SELECT keyspace_name FROM system_schema.keyspaces"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
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
        val sql = "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
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
        val sql = "SELECT column_name, type, kind FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val kind = rs.getString("kind") ?: ""
                        val isPrimaryKey = kind == "partition_key" || kind == "clustering"
                        add(ColumnInfo(
                            name = rs.getString("column_name"),
                            data_type = rs.getString("type") ?: "unknown",
                            is_nullable = !isPrimaryKey,
                            column_default = null,
                            is_primary_key = isPrimaryKey,
                            extra = if (kind.isNotBlank()) kind else null,
                            comment = null
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        val sql = "SELECT index_name, options FROM system_schema.indexes WHERE keyspace_name = ? AND table_name = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val indexName = rs.getString("index_name") ?: ""
                        val options = rs.getString("options") ?: ""
                        // Extract target column from options map if possible
                        val targetMatch = Regex("target[\"']?\\s*[:=]\\s*[\"']?([\\w]+)").find(options)
                        val columns = if (targetMatch != null) listOf(targetMatch.groupValues[1]) else emptyList()
                        add(IndexInfo(
                            name = indexName,
                            columns = columns,
                            is_unique = false,
                            is_primary = false,
                            filter = null,
                            index_type = null
                        ))
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, valueReader = ::stringResultValue)
    }

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
    JsonRpcServer(CassandraAgent()).run()
}
