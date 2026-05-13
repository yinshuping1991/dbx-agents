package com.dbx.agent.informix

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class InformixAgentExecutionTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = InformixAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("EXECUTE PROCEDURE sample_proc()", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
