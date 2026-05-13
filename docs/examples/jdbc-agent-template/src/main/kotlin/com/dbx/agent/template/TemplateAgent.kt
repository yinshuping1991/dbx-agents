package com.dbx.agent.template

import com.dbx.agent.ColumnInfo
import com.dbx.agent.ConnectParams
import com.dbx.agent.DatabaseAgent
import com.dbx.agent.DatabaseInfo
import com.dbx.agent.ForeignKeyInfo
import com.dbx.agent.IndexInfo
import com.dbx.agent.JdbcExecutor
import com.dbx.agent.JdbcIdentifiers
import com.dbx.agent.JsonRpcServer
import com.dbx.agent.QueryResult
import com.dbx.agent.TableInfo
import com.dbx.agent.TriggerInfo
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class TemplateAgent : DatabaseAgent {
    private var connection: Connection? = null
    private var databaseName: String = ""

    override fun getConnection(): Connection? = connection

    override fun connect(params: ConnectParams) {
        Class.forName(DRIVER_CLASS)
        connection = DriverManager.getConnection(jdbcUrl(params), params.username, params.password)
        databaseName = params.database
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName(DRIVER_CLASS)
        DriverManager.getConnection(jdbcUrl(params), params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        return listOf(DatabaseInfo(databaseName.ifBlank { "default" }))
    }

    override fun listSchemas(): List<String> {
        val conn = requireConnection()
        return conn.metaData.schemas.use { rs ->
            buildList {
                while (rs.next()) {
                    add(rs.getString("TABLE_SCHEM"))
                }
            }.sorted()
        }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val types = arrayOf("TABLE", "VIEW")
        return conn.metaData.getTables(null, schema, "%", types).use { rs ->
            buildList {
                while (rs.next()) {
                    add(TableInfo(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")))
                }
            }.sortedBy { it.name }
        }
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()
        val primaryKeys = conn.metaData.getPrimaryKeys(null, schema, table).use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getString("COLUMN_NAME"))
                }
            }
        }

        return conn.metaData.getColumns(null, schema, table, "%").use { rs ->
            buildList {
                while (rs.next()) {
                    val name = rs.getString("COLUMN_NAME")
                    add(
                        ColumnInfo(
                            name = name,
                            data_type = rs.getString("TYPE_NAME"),
                            is_nullable = rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable,
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
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        val columnsByIndex = linkedMapOf<String, MutableList<String>>()
        val uniqueByIndex = mutableMapOf<String, Boolean>()

        conn.metaData.getIndexInfo(null, schema, table, false, false).use { rs ->
            while (rs.next()) {
                val name = rs.getString("INDEX_NAME") ?: continue
                val column = rs.getString("COLUMN_NAME") ?: continue
                columnsByIndex.getOrPut(name) { mutableListOf() }.add(column)
                uniqueByIndex[name] = !rs.getBoolean("NON_UNIQUE")
            }
        }

        return columnsByIndex.map { (name, columns) ->
            IndexInfo(
                name = name,
                columns = columns,
                is_unique = uniqueByIndex[name] ?: false,
                is_primary = false,
                index_type = null,
            )
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = requireConnection()
        return conn.metaData.getImportedKeys(null, schema, table).use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        ForeignKeyInfo(
                            name = rs.getString("FK_NAME"),
                            column = rs.getString("FKCOLUMN_NAME"),
                            ref_table = rs.getString("PKTABLE_NAME"),
                            ref_column = rs.getString("PKCOLUMN_NAME"),
                        )
                    )
                }
            }
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        return emptyList()
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL)
    }

    override fun setSchemaSQL(schema: String): String {
        return "SET SCHEMA ${JdbcIdentifiers.doubleQuote(schema)}"
    }

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun jdbcUrl(params: ConnectParams): String {
        return "jdbc:template://${params.host}:${params.port}/${params.database}"
    }

    private fun stringResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
        return rs.getString(index)
    }

    private companion object {
        const val DRIVER_CLASS = "com.example.jdbc.TemplateDriver"
    }
}

fun main() {
    JsonRpcServer(TemplateAgent()).run()
}
