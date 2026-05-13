package com.dbx.agent.dameng

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class DamengAgentTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = DamengAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("CALL SP_SAMPLE()", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
