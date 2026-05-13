package com.dbx.agent.db2

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class Db2AgentTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = Db2Agent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("CALL ADMIN_CMD('list applications')", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
