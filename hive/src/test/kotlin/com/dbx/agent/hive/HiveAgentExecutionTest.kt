package com.dbx.agent.hive

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class HiveAgentExecutionTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = HiveAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("MSCK REPAIR TABLE sample", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
