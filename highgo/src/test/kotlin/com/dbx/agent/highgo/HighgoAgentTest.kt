package com.dbx.agent.highgo

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HighgoAgentTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return HighgoAgent()
    }

    override fun resultSetSql(): String = "CALL sample_proc()"

    @Test
    fun `declares highgo postgres-like profile`() {
        val agent = HighgoAgent()

        assertEquals("com.highgo.jdbc.Driver", agent.profile.driverClass)
        assertEquals("jdbc:highgo://{host}:{port}/{database}", agent.profile.urlTemplate)
    }
}
