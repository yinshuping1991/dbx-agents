package com.dbx.agent.test

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

abstract class JdbcMetadataBehaviorTest : JdbcConnectedAgentTest() {
    protected abstract fun metadataFixtureSql(): List<String>

    protected abstract fun metadataSchema(): String

    protected abstract fun expectedTablesInOrder(): List<String>

    protected abstract fun metadataColumnsTable(): String

    protected abstract fun expectedColumnsInOrder(): List<String>

    @Test
    fun `returns metadata in stable order`() {
        withAgent("dbx-agent-metadata") { agent ->
            metadataFixtureSql().forEach { sql ->
                agent.executeQuery(sql, metadataSchema())
            }

            val expectedTables = expectedTablesInOrder()
            val tableNames = agent.listTables(metadataSchema())
                .map { it.name }
                .filter { it in expectedTables }
            assertEquals(expectedTables, tableNames)

            val expectedColumns = expectedColumnsInOrder()
            val columnNames = agent.getColumns(metadataSchema(), metadataColumnsTable())
                .map { it.name }
                .filter { it in expectedColumns }
            assertEquals(expectedColumns, columnNames)
        }
    }
}
