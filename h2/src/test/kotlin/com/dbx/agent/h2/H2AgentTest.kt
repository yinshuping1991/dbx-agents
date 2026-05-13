package com.dbx.agent.h2

import com.dbx.agent.ConnectParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class H2AgentTest {
    @Test
    fun `executes statements that return result sets without SELECT prefix`() {
        val agent = H2Agent()
        agent.connect(ConnectParams(database = "mem:dbx-agent-call;DB_CLOSE_DELAY=-1"))

        val result = agent.executeQuery("CALL 42", null)

        assertEquals(listOf("42"), result.columns)
        assertEquals(listOf(listOf(42)), result.rows)
        assertEquals(0, result.affected_rows)
        assertFalse(result.truncated)
        agent.disconnect()
    }

    @Test
    fun `does not mark exactly max rows as truncated`() {
        val agent = H2Agent()
        agent.connect(ConnectParams(database = "mem:dbx-agent-limit;DB_CLOSE_DELAY=-1"))

        val result = agent.executeQuery(
            "SELECT X FROM SYSTEM_RANGE(1, 10000)",
            null,
        )

        assertEquals(10000, result.rows.size)
        assertFalse(result.truncated)
        agent.disconnect()
    }

    @Test
    fun `marks results beyond max rows as truncated`() {
        val agent = H2Agent()
        agent.connect(ConnectParams(database = "mem:dbx-agent-truncated;DB_CLOSE_DELAY=-1"))

        val result = agent.executeQuery(
            "SELECT X FROM SYSTEM_RANGE(1, 10001)",
            null,
        )

        assertEquals(10000, result.rows.size)
        assertTrue(result.truncated)
        agent.disconnect()
    }
}
