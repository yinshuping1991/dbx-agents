package com.dbx.agent.vastbase

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VastbaseAgentTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return VastbaseAgent()
    }

    override fun resultSetSql(): String = "CALL sample_proc()"

    @Test
    fun `declares vastbase postgres-like profile`() {
        val agent = VastbaseAgent()

        assertEquals("cn.com.vastbase.Driver", agent.profile.driverClass)
        assertEquals("jdbc:vastbase://{host}:{port}/{database}", agent.profile.urlTemplate)
    }
}
