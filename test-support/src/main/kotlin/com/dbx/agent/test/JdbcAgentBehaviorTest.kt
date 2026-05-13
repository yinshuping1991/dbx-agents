package com.dbx.agent.test

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.JdbcExecutor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

abstract class JdbcAgentBehaviorTest {
    protected abstract fun createConnectedAgent(databaseName: String): DatabaseAgent

    protected abstract fun resultSetSql(): String

    protected abstract fun expectedResultSetColumns(): List<String>

    protected abstract fun expectedResultSetRows(): List<List<Any?>>

    protected abstract fun rowsSql(rowCount: Int): String

    protected abstract fun metadataFixtureSql(): List<String>

    protected abstract fun metadataSchema(): String

    protected abstract fun expectedTablesInOrder(): List<String>

    protected abstract fun metadataColumnsTable(): String

    protected abstract fun expectedColumnsInOrder(): List<String>

    @Test
    fun `executes statements that return result sets without SELECT prefix`() {
        withAgent("dbx-agent-result-set") { agent ->
            val result = agent.executeQuery(resultSetSql(), null)

            assertEquals(expectedResultSetColumns(), result.columns)
            assertEquals(expectedResultSetRows(), result.rows)
            assertEquals(0, result.affected_rows)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `does not mark exactly max rows as truncated`() {
        withAgent("dbx-agent-limit") { agent ->
            val result = agent.executeQuery(rowsSql(JdbcExecutor.DEFAULT_MAX_ROWS), null)

            assertEquals(JdbcExecutor.DEFAULT_MAX_ROWS, result.rows.size)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `marks results beyond max rows as truncated`() {
        withAgent("dbx-agent-truncated") { agent ->
            val result = agent.executeQuery(rowsSql(JdbcExecutor.DEFAULT_MAX_ROWS + 1), null)

            assertEquals(JdbcExecutor.DEFAULT_MAX_ROWS, result.rows.size)
            assertTrue(result.truncated)
        }
    }

    @Test
    fun `handles transaction control statements consistently`() {
        withAgent("dbx-agent-transaction") { agent ->
            val connection = assertNotNull(agent.getConnection())

            val begin = agent.executeQuery("BEGIN", null)
            assertFalse(connection.autoCommit)
            assertEquals(emptyList(), begin.columns)
            assertEquals(emptyList(), begin.rows)
            assertEquals(0, begin.affected_rows)
            assertFalse(begin.truncated)

            val commit = agent.executeQuery("COMMIT", null)
            assertTrue(connection.autoCommit)
            assertEquals(emptyList(), commit.columns)
            assertEquals(emptyList(), commit.rows)
            assertEquals(0, commit.affected_rows)
            assertFalse(commit.truncated)

            agent.executeQuery("BEGIN", null)
            assertFalse(connection.autoCommit)

            val rollback = agent.executeQuery("ROLLBACK", null)
            assertTrue(connection.autoCommit)
            assertEquals(emptyList(), rollback.columns)
            assertEquals(emptyList(), rollback.rows)
            assertEquals(0, rollback.affected_rows)
            assertFalse(rollback.truncated)
        }
    }

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

    private fun withAgent(databaseName: String, block: (DatabaseAgent) -> Unit) {
        val agent = createConnectedAgent(databaseName)
        try {
            block(agent)
        } finally {
            agent.disconnect()
        }
    }
}
