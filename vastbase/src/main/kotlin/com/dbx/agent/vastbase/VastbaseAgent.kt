package com.dbx.agent.vastbase

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class VastbaseAgent : DatabaseAgent {
    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection
    override fun connect(params: ConnectParams) {
        Class.forName("cn.com.vastbase.Driver")
        val url = "jdbc:vastbase://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("cn.com.vastbase.Driver")
        val url = "jdbc:vastbase://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<DatabaseInfo>()
        conn.prepareStatement("SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(DatabaseInfo(rs.getString("datname")))
                }
            }
        }
        return result
    }

    override fun listSchemas(): List<String> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog','information_schema','pg_toast') ORDER BY schema_name"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(rs.getString("schema_name"))
                }
            }
        }
        return result
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<TableInfo>()
        conn.prepareStatement(
            "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name"
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val tableType = rs.getString("table_type").let {
                        if (it == "BASE TABLE") "TABLE" else it
                    }
                    result.add(TableInfo(rs.getString("table_name"), tableType))
                }
            }
        }
        return result
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")

        // Get primary key columns
        val primaryKeys = mutableSetOf<String>()
        conn.prepareStatement(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
                AND tc.table_schema = ?
                AND tc.table_name = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    primaryKeys.add(rs.getString("column_name"))
                }
            }
        }

        // Get columns
        val result = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """
            SELECT column_name, data_type, is_nullable, column_default,
                   numeric_precision, numeric_scale, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    result.add(
                        ColumnInfo(
                            name = colName,
                            data_type = rs.getString("data_type"),
                            is_nullable = rs.getString("is_nullable") == "YES",
                            column_default = rs.getString("column_default"),
                            is_primary_key = colName in primaryKeys,
                            numeric_precision = rs.getObject("numeric_precision") as? Int,
                            numeric_scale = rs.getObject("numeric_scale") as? Int,
                            character_maximum_length = rs.getObject("character_maximum_length")?.let {
                                (it as Number).toInt()
                            }
                        )
                    )
                }
            }
        }
        return result
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<IndexInfo>()
        conn.prepareStatement(
            """
            SELECT
                i.relname AS index_name,
                am.amname AS index_type,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary,
                array_agg(a.attname ORDER BY k.n) AS columns
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_am am ON am.oid = i.relam
            CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, n)
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE n.nspname = ? AND t.relname = ?
            GROUP BY i.relname, am.amname, ix.indisunique, ix.indisprimary
            ORDER BY i.relname
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val columnsArray = rs.getArray("columns")
                    val columns = (columnsArray.array as Array<*>).map { it.toString() }
                    result.add(
                        IndexInfo(
                            name = rs.getString("index_name"),
                            columns = columns,
                            is_unique = rs.getBoolean("is_unique"),
                            is_primary = rs.getBoolean("is_primary"),
                            index_type = rs.getString("index_type")
                        )
                    )
                }
            }
        }
        return result
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<ForeignKeyInfo>()
        conn.prepareStatement(
            """
            SELECT
                tc.constraint_name,
                kcu.column_name,
                ccu.table_name AS ref_table,
                ccu.column_name AS ref_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
                ON tc.constraint_name = ccu.constraint_name
                AND tc.table_schema = ccu.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
                AND tc.table_schema = ?
                AND tc.table_name = ?
            ORDER BY tc.constraint_name
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        ForeignKeyInfo(
                            name = rs.getString("constraint_name"),
                            column = rs.getString("column_name"),
                            ref_table = rs.getString("ref_table"),
                            ref_column = rs.getString("ref_column")
                        )
                    )
                }
            }
        }
        return result
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<TriggerInfo>()
        conn.prepareStatement(
            """
            SELECT trigger_name, event_manipulation, action_timing
            FROM information_schema.triggers
            WHERE trigger_schema = ? AND event_object_table = ?
            ORDER BY trigger_name
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        TriggerInfo(
                            name = rs.getString("trigger_name"),
                            event = rs.getString("event_manipulation"),
                            timing = rs.getString("action_timing")
                        )
                    )
                }
            }
        }
        return result
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(
            conn = connection ?: throw IllegalStateException("Not connected"),
            sql = sql,
            schema = schema,
            setSchemaSql = ::setSchemaSQL,
            valueReader = ::getResultValue,
        )
    }

    override fun setSchemaSQL(schema: String): String = "SET search_path TO ${JdbcIdentifiers.doubleQuote(schema)}"

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun getResultValue(rs: ResultSet, index: Int, sqlType: Int): Any? {
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

fun main() {
    JsonRpcServer(VastbaseAgent()).run()
}
