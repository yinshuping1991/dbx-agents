package com.dbx.agent.snowflake

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class SnowflakeAgentTest {
    @Test
    fun `executes non select statements that return result sets`() {
        val agent = SnowflakeAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        val result = agent.executeQuery("CALL sample_proc()", null)

        assertEquals(listOf("VALUE"), result.columns)
        assertEquals(listOf(listOf("row-value")), result.rows)
        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
