package com.dbx.agent

interface DatabaseAgent {
    fun connect(params: ConnectParams)
    fun testConnection(params: ConnectParams): Boolean
    fun listDatabases(): List<DatabaseInfo>
    fun listSchemas(): List<String>
    fun listTables(schema: String): List<TableInfo>
    fun getColumns(schema: String, table: String): List<ColumnInfo>
    fun listIndexes(schema: String, table: String): List<IndexInfo>
    fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo>
    fun listTriggers(schema: String, table: String): List<TriggerInfo>
    fun executeQuery(sql: String, schema: String?): QueryResult
    fun disconnect()
}
