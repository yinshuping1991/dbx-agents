package com.dbx.agent.kingbase

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KingbaseAgentTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return KingbaseAgent()
    }

    override fun resultSetSql(): String = "CALL sample_proc()"

    @Test
    fun `declares kingbase postgres-like profile`() {
        val agent = KingbaseAgent()

        assertEquals("com.kingbase8.Driver", agent.profile.driverClass)
        assertEquals("jdbc:kingbase8://{host}:{port}/{database}", agent.profile.urlTemplate)
    }
}
