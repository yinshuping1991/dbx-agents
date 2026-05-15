package com.dbx.agent

import java.sql.Connection

interface DatabaseAgent {
    fun connect(params: ConnectParams)
    fun testConnection(params: ConnectParams): Boolean
    fun listDatabases(): List<DatabaseInfo>
    fun listSchemas(): List<String>
    fun listTables(schema: String): List<TableInfo>
    fun listObjects(schema: String): List<ObjectInfo> {
        return listTables(schema).map { ObjectInfo(name = it.name, object_type = it.table_type, schema = schema, comment = it.comment) }
    }
    fun getColumns(schema: String, table: String): List<ColumnInfo>
    fun getObjectSource(schema: String, name: String, objectType: String): ObjectSource {
        throw UnsupportedOperationException("Object source is not supported")
    }
    fun listIndexes(schema: String, table: String): List<IndexInfo>
    fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo>
    fun listTriggers(schema: String, table: String): List<TriggerInfo>
    fun executeQuery(sql: String, schema: String?): QueryResult
    fun disconnect()
    fun getConnection(): Connection?
    fun executeTransaction(statements: List<String>, schema: String?): QueryResult {
        val conn = getConnection() ?: throw IllegalStateException("Not connected")
        val savedAutoCommit = conn.autoCommit
        conn.autoCommit = false
        val start = System.currentTimeMillis()
        try {
            if (!schema.isNullOrBlank()) {
                conn.createStatement().use { it.execute(setSchemaSQL(schema)) }
            }
            var totalAffected = 0L
            for (sql in statements) {
                conn.createStatement().use { stmt ->
                    totalAffected += stmt.executeUpdate(sql.trim().trimEnd(';'))
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
    fun setSchemaSQL(schema: String): String = "SET SCHEMA \"$schema\""
}
