package com.dbx.agent.goldendb

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class GoldendbAgent : DatabaseAgent {
    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection
    override fun connect(params: ConnectParams) {
        Class.forName("com.mysql.cj.jdbc.Driver")
        val url = "jdbc:mysql://${params.host}:${params.port}/${params.database}?useSSL=false&allowPublicKeyRetrieval=true"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.mysql.cj.jdbc.Driver")
        val url = "jdbc:mysql://${params.host}:${params.port}/${params.database}?useSSL=false&allowPublicKeyRetrieval=true"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<DatabaseInfo>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
                while (rs.next()) {
                    result.add(DatabaseInfo(rs.getString(1)))
                }
            }
        }
        return result
    }

    override fun listSchemas(): List<String> {
        // In MySQL, schemas and databases are the same concept
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<TableInfo>()
        conn.prepareStatement(
            "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME"
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val tableType = rs.getString("TABLE_TYPE").let {
                        if (it == "BASE TABLE") "TABLE" else it
                    }
                    result.add(TableInfo(rs.getString("TABLE_NAME"), tableType))
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
            SELECT COLUMN_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ?
                AND TABLE_NAME = ?
                AND CONSTRAINT_NAME = 'PRIMARY'
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"))
                }
            }
        }

        // Get columns
        val result = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT,
                   NUMERIC_PRECISION, NUMERIC_SCALE, CHARACTER_MAXIMUM_LENGTH
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("COLUMN_NAME")
                    result.add(
                        ColumnInfo(
                            name = colName,
                            data_type = rs.getString("COLUMN_TYPE"),
                            is_nullable = rs.getString("IS_NULLABLE") == "YES",
                            column_default = rs.getString("COLUMN_DEFAULT"),
                            is_primary_key = colName in primaryKeys,
                            extra = rs.getString("EXTRA"),
                            comment = rs.getString("COLUMN_COMMENT")?.ifEmpty { null },
                            numeric_precision = rs.getObject("NUMERIC_PRECISION") as? Int,
                            numeric_scale = rs.getObject("NUMERIC_SCALE") as? Int,
                            character_maximum_length = rs.getObject("CHARACTER_MAXIMUM_LENGTH")?.let {
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
        conn.createStatement().use { stmt ->
            val quotedTable = JdbcIdentifiers.backtick(table)
            val quotedSchema = JdbcIdentifiers.backtick(schema)
            stmt.executeQuery("SHOW INDEX FROM $quotedTable FROM $quotedSchema").use { rs ->
                val indexMap = linkedMapOf<String, MutableList<Pair<Int, String>>>()
                val uniqueMap = mutableMapOf<String, Boolean>()
                val typeMap = mutableMapOf<String, String>()

                while (rs.next()) {
                    val indexName = rs.getString("Key_name")
                    val colName = rs.getString("Column_name")
                    val seqInIndex = rs.getInt("Seq_in_index")
                    val nonUnique = rs.getInt("Non_unique")
                    val indexType = rs.getString("Index_type")

                    indexMap.getOrPut(indexName) { mutableListOf() }.add(seqInIndex to colName)
                    uniqueMap[indexName] = nonUnique == 0
                    typeMap[indexName] = indexType
                }

                return indexMap.toSortedMap().map { (name, cols) ->
                    IndexInfo(
                        name = name,
                        columns = cols.sortedBy { it.first }.map { it.second },
                        is_unique = uniqueMap[name] ?: false,
                        is_primary = name == "PRIMARY",
                        index_type = typeMap[name]
                    )
                }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val result = mutableListOf<ForeignKeyInfo>()
        conn.prepareStatement(
            """
            SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ?
                AND TABLE_NAME = ?
                AND REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY CONSTRAINT_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        ForeignKeyInfo(
                            name = rs.getString("CONSTRAINT_NAME"),
                            column = rs.getString("COLUMN_NAME"),
                            ref_table = rs.getString("REFERENCED_TABLE_NAME"),
                            ref_column = rs.getString("REFERENCED_COLUMN_NAME")
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
            SELECT TRIGGER_NAME, EVENT_MANIPULATION, ACTION_TIMING
            FROM information_schema.TRIGGERS
            WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?
            ORDER BY TRIGGER_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        TriggerInfo(
                            name = rs.getString("TRIGGER_NAME"),
                            event = rs.getString("EVENT_MANIPULATION"),
                            timing = rs.getString("ACTION_TIMING")
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

    override fun setSchemaSQL(schema: String): String = "USE ${JdbcIdentifiers.backtick(schema)}"

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
    JsonRpcServer(GoldendbAgent()).run()
}
