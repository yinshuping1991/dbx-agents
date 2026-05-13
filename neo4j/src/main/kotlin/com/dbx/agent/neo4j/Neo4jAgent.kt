package com.dbx.agent.neo4j

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager

class Neo4jAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun setSchemaSQL(schema: String): String = ""

    override fun connect(params: ConnectParams) {
        Class.forName("org.neo4j.jdbc.Neo4jDriver")
        val url = "jdbc:neo4j://${params.host}:${params.port}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("org.neo4j.jdbc.Neo4jDriver")
        val url = "jdbc:neo4j://${params.host}:${params.port}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val sql = "SHOW DATABASES"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString("name")))
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listSchemas(): List<String> {
        // Neo4j has no schemas
        return emptyList()
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val sql = "CALL db.labels()"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
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
        val sql = "CALL db.schema.nodeTypeProperties()"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        val nodeLabels = rs.getString("nodeLabels") ?: ""
                        // nodeLabels is typically like ["Label"]
                        if (nodeLabels.contains(table)) {
                            val propertyName = rs.getString("propertyName") ?: continue
                            val propertyTypes = rs.getString("propertyTypes") ?: "Unknown"
                            val mandatory = try {
                                !rs.getBoolean("mandatory")
                            } catch (_: Exception) {
                                true
                            }

                            add(ColumnInfo(
                                name = propertyName,
                                data_type = propertyTypes,
                                is_nullable = mandatory,
                                column_default = null,
                                is_primary_key = false,
                                extra = null,
                                comment = null
                            ))
                        }
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        val sql = "SHOW INDEXES"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        val labelsOrTypes = try { rs.getString("labelsOrTypes") ?: "" } catch (_: Exception) { "" }
                        if (labelsOrTypes.contains(table)) {
                            val properties = try { rs.getString("properties") ?: "" } catch (_: Exception) { "" }
                            val columns = properties.removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"") }
                                .filter { it.isNotBlank() }
                            val uniqueness = try { rs.getString("uniqueness") ?: "" } catch (_: Exception) { "" }
                            add(IndexInfo(
                                name = try { rs.getString("name") ?: "" } catch (_: Exception) { "" },
                                columns = columns,
                                is_unique = uniqueness.uppercase() == "UNIQUE",
                                is_primary = false,
                                filter = null,
                                index_type = try { rs.getString("type") } catch (_: Exception) { null }
                            ))
                        }
                    }
                }.sortedBy { it.name }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        // Graph DB has relationships, not foreign keys
        return emptyList()
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        return emptyList()
    }

    override fun executeTransaction(statements: List<String>, schema: String?): QueryResult {
        val conn = requireConnection()
        val savedAutoCommit = conn.autoCommit
        conn.autoCommit = false
        val start = System.currentTimeMillis()
        try {
            var totalAffected = 0L
            for (sql in statements) {
                conn.createStatement().use { stmt ->
                    stmt.execute(sql.trim().trimEnd(';'))
                    totalAffected += stmt.updateCount.coerceAtLeast(0).toLong()
                }
            }
            conn.commit()
            return QueryResult(emptyList(), emptyList(), totalAffected, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = savedAutoCommit
        }
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
    JsonRpcServer(Neo4jAgent()).run()
}
