package com.dbx.agent

data class DatabaseInfo(val name: String)

data class TableInfo(
    val name: String,
    val table_type: String,
    val comment: String? = null
)

data class ColumnInfo(
    val name: String,
    val data_type: String,
    val is_nullable: Boolean,
    val column_default: String? = null,
    val is_primary_key: Boolean,
    val extra: String? = null,
    val comment: String? = null,
    val numeric_precision: Int? = null,
    val numeric_scale: Int? = null,
    val character_maximum_length: Int? = null
)

data class IndexInfo(
    val name: String,
    val columns: List<String>,
    val is_unique: Boolean,
    val is_primary: Boolean,
    val filter: String? = null,
    val index_type: String? = null,
    val included_columns: List<String>? = null,
    val comment: String? = null
)

data class ForeignKeyInfo(
    val name: String,
    val column: String,
    val ref_table: String,
    val ref_column: String
)

data class TriggerInfo(
    val name: String,
    val event: String,
    val timing: String
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val affected_rows: Long,
    val execution_time_ms: Long,
    val truncated: Boolean = false
)

data class ExecuteQueryOptions(
    val maxRows: Int = JdbcExecutor.DEFAULT_MAX_ROWS,
    val fetchSize: Int? = null
)

data class QueryPageOptions(
    val pageSize: Int = 100,
    val fetchSize: Int? = null,
    val maxRows: Int = JdbcExecutor.DEFAULT_MAX_ROWS
)

data class QueryPageResult(
    val columns: List<String>,
    val rows: List<List<Any?>>,
    val affected_rows: Long,
    val execution_time_ms: Long,
    val truncated: Boolean = false,
    val session_id: String? = null,
    val has_more: Boolean = false
)

data class ObjectInfo(
    val name: String,
    val object_type: String,
    val schema: String? = null,
    val comment: String? = null
)

data class ObjectSource(
    val name: String,
    val object_type: String,
    val schema: String? = null,
    val source: String
)

data class ConnectParams(
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val url_params: String = "",
    val connection_string: String = ""
)

data class SchemaTableParams(
    val schema: String,
    val table: String
)

data class ExecuteQueryParams(
    val sql: String,
    val schema: String? = null,
    val maxRows: Int? = null,
    val fetchSize: Int? = null
)

data class QueryPageParams(
    val sql: String,
    val schema: String? = null,
    val pageSize: Int? = null,
    val fetchSize: Int? = null,
    val maxRows: Int? = null
)
