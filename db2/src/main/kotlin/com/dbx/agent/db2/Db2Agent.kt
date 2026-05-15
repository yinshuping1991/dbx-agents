package com.dbx.agent.db2

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager

class Db2Agent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    override fun setSchemaSQL(schema: String): String = "SET SCHEMA ${JdbcIdentifiers.doubleQuote(schema)}"

    override fun connect(params: ConnectParams) {
        Class.forName("com.ibm.db2.jcc.DB2Driver")
        val url = "jdbc:db2://${params.host}:${params.port}/${params.database}"
        connection = DriverManager.getConnection(url, params.username, params.password)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("com.ibm.db2.jcc.DB2Driver")
        val url = "jdbc:db2://${params.host}:${params.port}/${params.database}"
        DriverManager.getConnection(url, params.username, params.password).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val sql = "SELECT CURRENT_SERVER FROM SYSIBM.SYSDUMMY1"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString(1).trim()))
                    }
                }
            }
        }
    }

    override fun listSchemas(): List<String> {
        val conn = requireConnection()
        val sql = "SELECT SCHEMANAME FROM SYSCAT.SCHEMATA WHERE OWNERTYPE = 'U' ORDER BY SCHEMANAME"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(rs.getString(1).trim())
                    }
                }
            }
        }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val sql = "SELECT TABNAME, TYPE FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TYPE IN ('T','V') ORDER BY TABNAME"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val type = when (rs.getString(2).trim()) {
                            "T" -> "TABLE"
                            "V" -> "VIEW"
                            else -> rs.getString(2).trim()
                        }
                        add(TableInfo(
                            name = rs.getString(1).trim(),
                            table_type = type
                        ))
                    }
                }
            }
        }
    }

    override fun listObjects(schema: String): List<ObjectInfo> {
        val result = listTables(schema).map { ObjectInfo(name = it.name, object_type = it.table_type, schema = schema, comment = it.comment) }.toMutableList()
        val conn = requireConnection()
        conn.prepareStatement(
            "SELECT PROCNAME, 'PROCEDURE' FROM SYSCAT.PROCEDURES WHERE PROCSCHEMA = ? ORDER BY PROCNAME"
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(ObjectInfo(name = rs.getString(1).trim(), object_type = rs.getString(2), schema = schema))
                }
            }
        }
        return result
    }

    override fun getObjectSource(schema: String, name: String, objectType: String): ObjectSource {
        val conn = requireConnection()
        val sql = "SELECT TEXT FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA = ? AND ROUTINENAME = ?"
        val source = conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) ?: "" else ""
            }
        }
        return ObjectSource(name = name, object_type = objectType, schema = schema, source = source)
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()

        // Fetch primary key columns
        val pkColumns = mutableSetOf<String>()
        val pkSql = """
            SELECT kc.COLNAME FROM SYSCAT.KEYCOLUSE kc
            JOIN SYSCAT.TABCONST tc ON kc.CONSTNAME = tc.CONSTNAME AND kc.TABSCHEMA = tc.TABSCHEMA AND kc.TABNAME = tc.TABNAME
            WHERE tc.TYPE = 'P' AND tc.TABSCHEMA = ? AND tc.TABNAME = ?
        """.trimIndent()
        conn.prepareStatement(pkSql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    pkColumns.add(rs.getString(1).trim())
                }
            }
        }

        // Fetch column metadata
        val colSql = """
            SELECT COLNAME, TYPENAME, NULLS, DEFAULT, LENGTH, SCALE
            FROM SYSCAT.COLUMNS
            WHERE TABSCHEMA = ? AND TABNAME = ?
            ORDER BY COLNO
        """.trimIndent()
        conn.prepareStatement(colSql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val name = rs.getString("COLNAME").trim()
                        val typeName = rs.getString("TYPENAME").trim()
                        val length = rs.getObject("LENGTH")?.let { (it as Number).toInt() }
                        val scale = rs.getObject("SCALE")?.let { (it as Number).toInt() }
                        val dataType = formatDataType(typeName, length, scale)

                        add(ColumnInfo(
                            name = name,
                            data_type = dataType,
                            is_nullable = rs.getString("NULLS").trim() == "Y",
                            column_default = rs.getString("DEFAULT")?.trim(),
                            is_primary_key = pkColumns.contains(name),
                            extra = null,
                            comment = null,
                            numeric_precision = if (typeName in listOf("DECIMAL", "NUMERIC", "INTEGER", "SMALLINT", "BIGINT", "REAL", "DOUBLE", "FLOAT")) length else null,
                            numeric_scale = if (typeName in listOf("DECIMAL", "NUMERIC")) scale else null,
                            character_maximum_length = if (typeName in listOf("VARCHAR", "CHAR", "CLOB", "GRAPHIC", "VARGRAPHIC")) length else null
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        val sql = "SELECT INDNAME, COLNAMES, UNIQUERULE FROM SYSCAT.INDEXES WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY INDNAME"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colNames = rs.getString(2)?.trim() ?: ""
                        // DB2 COLNAMES format: +COL1+COL2 or -COL1-COL2
                        val columns = colNames.split(Regex("[+-]")).filter { it.isNotBlank() }
                        val uniqueRule = rs.getString(3)?.trim() ?: ""
                        add(IndexInfo(
                            name = rs.getString(1).trim(),
                            columns = columns,
                            is_unique = uniqueRule == "U" || uniqueRule == "P",
                            is_primary = uniqueRule == "P",
                            filter = null,
                            index_type = null
                        ))
                    }
                }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = requireConnection()
        val sql = "SELECT CONSTNAME, FK_COLNAMES, REFTABNAME, PK_COLNAMES FROM SYSCAT.REFERENCES WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY CONSTNAME"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val fkCols = rs.getString(2)?.trim() ?: ""
                        val pkCols = rs.getString(4)?.trim() ?: ""
                        // DB2 may return space-separated column names
                        val fkColList = fkCols.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val pkColList = pkCols.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val refTable = rs.getString(3).trim()
                        val constName = rs.getString(1).trim()
                        for (i in fkColList.indices) {
                            add(ForeignKeyInfo(
                                name = constName,
                                column = fkColList[i],
                                ref_table = refTable,
                                ref_column = if (i < pkColList.size) pkColList[i] else ""
                            ))
                        }
                    }
                }
            }
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        val conn = requireConnection()
        val sql = "SELECT TRIGNAME, TRIGEVENT, TRIGTIME FROM SYSCAT.TRIGGERS WHERE TABSCHEMA = ? AND TABNAME = ? ORDER BY TRIGNAME"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TriggerInfo(
                            name = rs.getString(1).trim(),
                            event = rs.getString(2).trim(),
                            timing = rs.getString(3).trim()
                        ))
                    }
                }
            }
        }
    }

    override fun executeQuery(sql: String, schema: String?): QueryResult {
        return JdbcExecutor.execute(requireConnection(), sql, schema, ::setSchemaSQL, valueReader = ::stringResultValue)
    }

    override fun disconnect() {
        connection?.close()
        connection = null
    }

    private fun requireConnection(): Connection {
        return connection ?: throw IllegalStateException("Not connected")
    }

    private fun formatDataType(typeName: String, length: Int?, scale: Int?): String {
        return when (typeName.uppercase()) {
            "VARCHAR", "CHAR", "CLOB", "GRAPHIC", "VARGRAPHIC" -> {
                if (length != null) "$typeName($length)" else typeName
            }
            "DECIMAL", "NUMERIC" -> {
                when {
                    length != null && scale != null && scale > 0 -> "$typeName($length,$scale)"
                    length != null -> "$typeName($length)"
                    else -> typeName
                }
            }
            else -> typeName
        }
    }

    private fun stringResultValue(rs: java.sql.ResultSet, index: Int, sqlType: Int): Any? {
        val value = rs.getObject(index)
        return if (rs.wasNull()) null else value?.toString()
    }
}

fun main() {
    JsonRpcServer(Db2Agent()).run()
}
