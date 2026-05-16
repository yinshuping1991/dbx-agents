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
    fun getTableDdl(schema: String, table: String): String {
        return buildTableDdl(
            schema = schema,
            table = table,
            columns = getColumns(schema, table),
            indexes = runCatching { listIndexes(schema, table) }.getOrDefault(emptyList()),
            foreignKeys = runCatching { listForeignKeys(schema, table) }.getOrDefault(emptyList()),
        )
    }
    fun listIndexes(schema: String, table: String): List<IndexInfo>
    fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo>
    fun listTriggers(schema: String, table: String): List<TriggerInfo>
    fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions = ExecuteQueryOptions()): QueryResult
    fun executeQueryPage(sql: String, schema: String?, options: QueryPageOptions = QueryPageOptions()): QueryPageResult {
        val conn = getConnection() ?: throw IllegalStateException("Not connected")
        return JdbcExecutor.executePage(conn, sql, schema, ::setSchemaSQL, options)
    }
    fun fetchQueryPage(sessionId: String, pageSize: Int): QueryPageResult {
        return JdbcExecutor.fetchPage(sessionId, pageSize)
    }
    fun closeQuerySession(sessionId: String): Boolean {
        return JdbcExecutor.closeQuerySession(sessionId)
    }
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

fun buildTableDdl(
    schema: String,
    table: String,
    columns: List<ColumnInfo>,
    indexes: List<IndexInfo>,
    foreignKeys: List<ForeignKeyInfo>,
): String {
    val tableRef = "${quoteIdent(schema)}.${quoteIdent(table)}"
    val columnLines = columns.map { column ->
        buildString {
            append("  ")
            append(quoteIdent(column.name))
            append(" ")
            append(columnTypeSql(column))
            if (!column.is_nullable) append(" NOT NULL")
            if (!column.column_default.isNullOrBlank()) append(" DEFAULT ${column.column_default}")
        }
    }.toMutableList()

    val primaryKeys = columns.filter { it.is_primary_key }.map { quoteIdent(it.name) }
    if (primaryKeys.isNotEmpty()) {
        columnLines.add("  PRIMARY KEY (${primaryKeys.joinToString(", ")})")
    }

    foreignKeys.forEach { fk ->
        columnLines.add(
            "  CONSTRAINT ${quoteIdent(fk.name)} FOREIGN KEY (${quoteIdent(fk.column)}) " +
                "REFERENCES ${quoteIdent(fk.ref_table)}(${quoteIdent(fk.ref_column)})"
        )
    }

    val ddl = StringBuilder()
    ddl.append("CREATE TABLE ")
    ddl.append(tableRef)
    ddl.append(" (\n")
    ddl.append(columnLines.joinToString(",\n"))
    ddl.append("\n);\n")

    indexes.filterNot { it.is_primary }.forEach { index ->
        val unique = if (index.is_unique) "UNIQUE " else ""
        val using = index.index_type?.takeIf { it.isNotBlank() }?.let { " USING $it" } ?: ""
        val cols = index.columns.joinToString(", ") { quoteIdent(it) }
        val filter = index.filter?.takeIf { it.isNotBlank() }?.let { " WHERE $it" } ?: ""
        ddl.append("\nCREATE ${unique}INDEX ${quoteIdent(index.name)} ON $tableRef$using ($cols)$filter;")
        index.comment?.takeIf { it.isNotBlank() }?.let { comment ->
            ddl.append("\nCOMMENT ON INDEX ${quoteIdent(schema)}.${quoteIdent(index.name)} IS '${comment.replace("'", "''")}';")
        }
    }

    return ddl.toString()
}

private fun quoteIdent(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

private fun columnTypeSql(column: ColumnInfo): String {
    val type = column.data_type
    val normalized = type.lowercase()
    if ((normalized == "character varying" || normalized == "varchar" || normalized == "char" || normalized == "character") &&
        column.character_maximum_length != null
    ) {
        return "$type(${column.character_maximum_length})"
    }
    if ((normalized == "numeric" || normalized == "decimal") && column.numeric_precision != null) {
        return if (column.numeric_scale != null) {
            "$type(${column.numeric_precision}, ${column.numeric_scale})"
        } else {
            "$type(${column.numeric_precision})"
        }
    }
    return type
}
