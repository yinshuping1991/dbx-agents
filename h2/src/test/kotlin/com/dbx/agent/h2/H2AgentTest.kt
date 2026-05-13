package com.dbx.agent.h2

import com.dbx.agent.ConnectParams
import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcAgentBehaviorTest

class H2AgentTest : JdbcAgentBehaviorTest() {
    override fun createConnectedAgent(databaseName: String): DatabaseAgent {
        return H2Agent().apply {
            connect(ConnectParams(database = "mem:$databaseName;DB_CLOSE_DELAY=-1"))
        }
    }

    override fun resultSetSql(): String = "CALL 42"

    override fun expectedResultSetColumns(): List<String> = listOf("42")

    override fun expectedResultSetRows(): List<List<Any?>> = listOf(listOf(42))

    override fun rowsSql(rowCount: Int): String = "SELECT X FROM SYSTEM_RANGE(1, $rowCount)"

    override fun metadataFixtureSql(): List<String> {
        return listOf(
            "CREATE TABLE BETA_TABLE (ID INT PRIMARY KEY)",
            "CREATE TABLE ALPHA_TABLE (ID INT PRIMARY KEY)",
            "CREATE TABLE COLUMN_ORDER_SAMPLE (ID INT PRIMARY KEY, NAME VARCHAR(64), CREATED_AT TIMESTAMP)",
        )
    }

    override fun metadataSchema(): String = "PUBLIC"

    override fun expectedTablesInOrder(): List<String> {
        return listOf("ALPHA_TABLE", "BETA_TABLE", "COLUMN_ORDER_SAMPLE")
    }

    override fun metadataColumnsTable(): String = "COLUMN_ORDER_SAMPLE"

    override fun expectedColumnsInOrder(): List<String> {
        return listOf("ID", "NAME", "CREATED_AT")
    }
}
