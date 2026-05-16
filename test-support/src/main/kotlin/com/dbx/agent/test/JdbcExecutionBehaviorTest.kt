package com.dbx.agent.test

import com.dbx.agent.JdbcExecutor
import com.dbx.agent.QueryPageOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

abstract class JdbcExecutionBehaviorTest : JdbcConnectedAgentTest() {
    protected abstract fun resultSetSql(): String

    protected abstract fun expectedResultSetColumns(): List<String>

    protected abstract fun expectedResultSetRows(): List<List<Any?>>

    protected abstract fun rowsSql(rowCount: Int): String

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
    fun `fetches subsequent result pages from the same jdbc session`() {
        withAgent("dbx-agent-session-pages") { agent ->
            val first = agent.executeQueryPage(rowsSql(5), null, QueryPageOptions(pageSize = 2, fetchSize = 2, maxRows = 10))

            assertEquals(listOf(listOf(1L), listOf(2L)), first.rows)
            assertTrue(first.has_more)
            assertFalse(first.truncated)
            val sessionId = assertNotNull(first.session_id)

            val second = agent.fetchQueryPage(sessionId, 2)

            assertEquals(listOf(listOf(3L), listOf(4L)), second.rows)
            assertTrue(second.has_more)
            assertFalse(second.truncated)

            val third = agent.fetchQueryPage(sessionId, 2)

            assertEquals(listOf(listOf(5L)), third.rows)
            assertFalse(third.has_more)
            assertFalse(third.truncated)
        }
    }

    @Test
    fun `closes jdbc result sessions explicitly`() {
        withAgent("dbx-agent-session-close") { agent ->
            val first = agent.executeQueryPage(rowsSql(5), null, QueryPageOptions(pageSize = 2, fetchSize = 2, maxRows = 10))
            val sessionId = assertNotNull(first.session_id)

            assertTrue(agent.closeQuerySession(sessionId))
            assertFalse(agent.closeQuerySession(sessionId))
            assertFailsWith<IllegalArgumentException> {
                agent.fetchQueryPage(sessionId, 2)
            }
        }
    }

    @Test
    fun `expires idle jdbc result sessions`() {
        withAgent("dbx-agent-session-expiry") { agent ->
            val first = agent.executeQueryPage(rowsSql(5), null, QueryPageOptions(pageSize = 2, fetchSize = 2, maxRows = 10))
            val sessionId = assertNotNull(first.session_id)

            assertEquals(1, JdbcExecutor.expireIdleQuerySessions(idleTimeoutMillis = 0))
            assertFailsWith<IllegalArgumentException> {
                agent.fetchQueryPage(sessionId, 2)
            }
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
}
