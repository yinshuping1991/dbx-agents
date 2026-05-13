package com.dbx.agent.template

import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateAgentTest {
    @Test
    fun `executeQuery uses Statement execute`() {
        val agent = TemplateAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        agent.executeQuery("SHOW TABLES", null)

        assertEquals(listOf("execute"), JdbcAgentFake.calls)
    }
}
