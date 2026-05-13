package com.dbx.agent.oracle10g

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class Oracle10gAgentTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = Oracle10gAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("CALL DBMS_XPLAN.DISPLAY_CURSOR()", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
