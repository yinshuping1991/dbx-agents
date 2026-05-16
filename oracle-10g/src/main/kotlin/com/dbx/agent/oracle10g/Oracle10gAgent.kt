package com.dbx.agent.oracle10g

import com.dbx.agent.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class Oracle10gAgent : DatabaseAgent {

    private var connection: Connection? = null

    override fun getConnection(): Connection? = connection

    companion object {
        private val SYSTEM_SCHEMAS = setOf(
            "SYS", "SYSTEM", "SYSMAN", "DBSNMP", "SYSBACKUP", "SYSDG", "SYSKM", "OUTLN",
            "AUDSYS", "LBACSYS", "DVF", "DVSYS", "APPQOSSYS", "CTXSYS", "MDSYS", "MDDATA",
            "ORDSYS", "ORDDATA", "ORDPLUGINS", "XDB", "ANONYMOUS", "DIP", "EXFSYS",
            "GSMADMIN_INTERNAL", "GSMCATUSER", "GSMUSER", "OJVMSYS", "OLAPSYS",
            "ORACLE_OCM", "SI_INFORMTN_SCHEMA", "WMSYS", "XS\$NULL", "DBSFWUSER",
            "REMOTE_SCHEDULER_AGENT", "PDBADMIN", "DGPDB_INT", "OPS\$ORACLE",
            "GGSYS", "FLOWS_FILES", "APEX_PUBLIC_USER"
        )

        private val OFFSET_FETCH_RE = Regex(
            """(.+?)\s+OFFSET\s+(\d+)\s+ROWS?\s+FETCH\s+(FIRST|NEXT)\s+(\d+)\s+ROWS?\s+ONLY""",
            RegexOption.IGNORE_CASE
        )
        private val FETCH_ONLY_RE = Regex(
            """(.+?)\s+FETCH\s+(FIRST|NEXT)\s+(\d+)\s+ROWS?\s+ONLY""",
            RegexOption.IGNORE_CASE
        )

        fun rewriteFetchFirst(sql: String): String {
            OFFSET_FETCH_RE.matchEntire(sql)?.let { m ->
                val innerSql = m.groupValues[1]
                val offset = m.groupValues[2].toLong()
                val limit = m.groupValues[4].toLong()
                val upper = offset + limit
                return "SELECT * FROM (SELECT a.*, ROWNUM rn__ FROM ($innerSql) a WHERE ROWNUM <= $upper) WHERE rn__ > $offset"
            }
            FETCH_ONLY_RE.matchEntire(sql)?.let { m ->
                val innerSql = m.groupValues[1]
                val limit = m.groupValues[3].toLong()
                return "SELECT * FROM ($innerSql) WHERE ROWNUM <= $limit"
            }
            return sql
        }
    }

    override fun setSchemaSQL(schema: String): String = "ALTER SESSION SET CURRENT_SCHEMA = ${JdbcIdentifiers.doubleQuote(schema)}"

    override fun connect(params: ConnectParams) {
        Class.forName("oracle.jdbc.OracleDriver")
        var serviceName = params.database
        val props = Properties()
        props["user"] = params.username
        props["password"] = params.password

        if (serviceName.uppercase().startsWith("SYSDBA:")) {
            serviceName = serviceName.substring(7)
            props["internal_logon"] = "SYSDBA"
        }

        val url = "jdbc:oracle:thin:@${params.host}:${params.port}/$serviceName"
        connection = DriverManager.getConnection(url, props)
    }

    override fun testConnection(params: ConnectParams): Boolean {
        Class.forName("oracle.jdbc.OracleDriver")
        var serviceName = params.database
        val props = Properties()
        props["user"] = params.username
        props["password"] = params.password

        if (serviceName.uppercase().startsWith("SYSDBA:")) {
            serviceName = serviceName.substring(7)
            props["internal_logon"] = "SYSDBA"
        }

        val url = "jdbc:oracle:thin:@${params.host}:${params.port}/$serviceName"
        DriverManager.getConnection(url, props).use { conn ->
            return conn.isValid(5)
        }
    }

    override fun listDatabases(): List<DatabaseInfo> {
        val conn = requireConnection()
        val placeholders = SYSTEM_SCHEMAS.joinToString(",") { "'$it'" }
        val sql = """
            SELECT owner FROM (
                SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS owner FROM DUAL
                UNION
                SELECT DISTINCT owner FROM all_tables
                UNION
                SELECT DISTINCT owner FROM all_views
            )
            WHERE owner IS NOT NULL
              AND owner NOT IN ($placeholders)
              AND owner NOT LIKE 'APEX_%'
              AND owner NOT LIKE 'FLOWS_%'
            ORDER BY owner
        """.trimIndent()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(DatabaseInfo(rs.getString(1)))
                    }
                }
            }
        }
    }

    override fun listSchemas(): List<String> {
        return listDatabases().map { it.name }
    }

    override fun listTables(schema: String): List<TableInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT o.OBJECT_NAME,
                CASE o.OBJECT_TYPE WHEN 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS TABLE_TYPE,
                c.COMMENTS
            FROM ALL_OBJECTS o
            LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = o.OWNER AND c.TABLE_NAME = o.OBJECT_NAME
            WHERE o.OWNER = ? AND o.OBJECT_TYPE IN ('TABLE','VIEW')
            ORDER BY o.OBJECT_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TableInfo(
                            name = rs.getString(1),
                            table_type = rs.getString(2),
                            comment = rs.getString(3)
                        ))
                    }
                }
            }
        }
    }

    override fun getTableDdl(schema: String, table: String): String {
        val conn = requireConnection()
        val sql = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, "TABLE")
            stmt.setString(2, table)
            stmt.setString(3, schema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getString(1) ?: ""
            }
        }
        throw IllegalArgumentException("Table not found: $schema.$table")
    }

    override fun getColumns(schema: String, table: String): List<ColumnInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT c.COLUMN_NAME, c.DATA_TYPE, c.NULLABLE, c.DATA_PRECISION, c.DATA_SCALE,
                c.DATA_LENGTH, c.CHAR_LENGTH, cc.COMMENTS,
                CASE WHEN pk.COLUMN_NAME IS NULL THEN 0 ELSE 1 END AS IS_PK
            FROM ALL_TAB_COLUMNS c
            LEFT JOIN ALL_COL_COMMENTS cc
                ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME
            LEFT JOIN (
                SELECT cols.COLUMN_NAME FROM ALL_CONS_COLUMNS cols
                JOIN ALL_CONSTRAINTS cons
                    ON cols.CONSTRAINT_NAME = cons.CONSTRAINT_NAME AND cols.OWNER = cons.OWNER
                WHERE cons.CONSTRAINT_TYPE = 'P' AND cons.OWNER = ? AND cons.TABLE_NAME = ?
            ) pk ON pk.COLUMN_NAME = c.COLUMN_NAME
            WHERE c.OWNER = ? AND c.TABLE_NAME = ?
            ORDER BY c.COLUMN_ID
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.setString(3, schema)
            stmt.setString(4, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val name = rs.getString("COLUMN_NAME")
                        val baseType = rs.getString("DATA_TYPE")
                        val numPrec = rs.getObject("DATA_PRECISION")?.let { (it as Number).toInt() }
                        val numScale = rs.getObject("DATA_SCALE")?.let { (it as Number).toInt() }
                        val dataLen = rs.getObject("DATA_LENGTH")?.let { (it as Number).toInt() }
                        val charLen = rs.getObject("CHAR_LENGTH")?.let { (it as Number).toInt() }
                        val dataType = formatDataType(baseType, numPrec, numScale, dataLen, charLen)

                        add(ColumnInfo(
                            name = name,
                            data_type = dataType,
                            is_nullable = rs.getString("NULLABLE") == "Y",
                            column_default = null,
                            is_primary_key = rs.getInt("IS_PK") == 1,
                            extra = null,
                            comment = rs.getString("COMMENTS"),
                            numeric_precision = numPrec,
                            numeric_scale = numScale,
                            character_maximum_length = charLen
                        ))
                    }
                }
            }
        }
    }

    override fun listIndexes(schema: String, table: String): List<IndexInfo> {
        val conn = requireConnection()
        // Use RTRIM+XMLAGG instead of LISTAGG for Oracle 10g compatibility
        val sql = """
            SELECT i.INDEX_NAME,
                RTRIM(XMLAGG(XMLELEMENT(e, ic.COLUMN_NAME || ',') ORDER BY ic.COLUMN_POSITION).EXTRACT('//text()').GETSTRINGVAL(), ',') AS COLUMNS,
                i.UNIQUENESS,
                CASE WHEN c.CONSTRAINT_TYPE = 'P' THEN 1 ELSE 0 END AS IS_PK,
                i.INDEX_TYPE
            FROM ALL_INDEXES i
            JOIN ALL_IND_COLUMNS ic ON i.INDEX_NAME = ic.INDEX_NAME AND i.OWNER = ic.INDEX_OWNER AND i.TABLE_OWNER = ic.TABLE_OWNER
            LEFT JOIN ALL_CONSTRAINTS c ON i.INDEX_NAME = c.INDEX_NAME AND i.TABLE_OWNER = c.OWNER
                AND c.CONSTRAINT_TYPE = 'P'
            WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ?
            GROUP BY i.INDEX_NAME, i.UNIQUENESS, c.CONSTRAINT_TYPE, i.INDEX_TYPE
            ORDER BY i.INDEX_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        val colsStr = rs.getString(2) ?: ""
                        add(IndexInfo(
                            name = rs.getString(1),
                            columns = colsStr.split(",").filter { it.isNotEmpty() },
                            is_unique = rs.getString(3) == "UNIQUE",
                            is_primary = rs.getString(4) == "1",
                            filter = null,
                            index_type = rs.getString(5)
                        ))
                    }
                }
            }
        }
    }

    override fun listForeignKeys(schema: String, table: String): List<ForeignKeyInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT c.CONSTRAINT_NAME, cc.COLUMN_NAME, rc.TABLE_NAME, rcc.COLUMN_NAME
            FROM ALL_CONSTRAINTS c
            JOIN ALL_CONS_COLUMNS cc ON c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME AND c.OWNER = cc.OWNER
            JOIN ALL_CONSTRAINTS rc ON c.R_CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND c.R_OWNER = rc.OWNER
            JOIN ALL_CONS_COLUMNS rcc ON rc.CONSTRAINT_NAME = rcc.CONSTRAINT_NAME AND rc.OWNER = rcc.OWNER
            WHERE c.CONSTRAINT_TYPE = 'R' AND c.OWNER = ? AND c.TABLE_NAME = ?
            ORDER BY c.CONSTRAINT_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(ForeignKeyInfo(
                            name = rs.getString(1),
                            column = rs.getString(2),
                            ref_table = rs.getString(3),
                            ref_column = rs.getString(4)
                        ))
                    }
                }
            }
        }
    }

    override fun listTriggers(schema: String, table: String): List<TriggerInfo> {
        val conn = requireConnection()
        val sql = """
            SELECT TRIGGER_NAME, TRIGGERING_EVENT, TRIGGER_TYPE
            FROM ALL_TRIGGERS
            WHERE OWNER = ? AND TABLE_NAME = ?
            ORDER BY TRIGGER_NAME
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(TriggerInfo(
                            name = rs.getString(1),
                            event = rs.getString(2),
                            timing = rs.getString(3)
                        ))
                    }
                }
            }
        }
    }

    override fun executeQuery(sql: String, schema: String?, options: ExecuteQueryOptions): QueryResult {
        return JdbcExecutor.execute(
            conn = requireConnection(),
            sql = rewriteFetchFirst(sql.trim().trimEnd(';')),
            schema = schema,
            setSchemaSql = ::setSchemaSQL,
            maxRows = options.maxRows,
            fetchSize = options.fetchSize,
            valueReader = ::stringResultValue,
        )
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

    private fun formatDataType(
        base: String,
        numPrec: Int?,
        numScale: Int?,
        dataLen: Int?,
        charLen: Int?
    ): String {
        return when (base.uppercase()) {
            "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> {
                val len = charLen ?: dataLen
                if (len != null) "$base($len)" else base
            }
            "NUMBER" -> {
                when {
                    numPrec != null && numScale != null && numScale > 0 -> "$base($numPrec,$numScale)"
                    numPrec != null && numPrec > 0 -> "$base($numPrec)"
                    else -> base
                }
            }
            "RAW" -> {
                if (dataLen != null) "RAW($dataLen)" else "RAW"
            }
            else -> base
        }
    }
}

fun main() {
    JsonRpcServer(Oracle10gAgent()).run()
}
