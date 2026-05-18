package com.dbx.agent.yashandb

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YashandbAgentTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return YashandbAgent()
    }

    override fun resultSetSql(): String = "SELECT 1 FROM DUAL"

    @Test
    fun `declares yashandb jdbc profile`() {
        val profile = YashandbAgent().profile

        assertEquals("com.yashandb.jdbc.Driver", profile.driverClass)
        assertEquals("jdbc:yasdb://{host}:{port}/{database}", profile.urlTemplate)
        assertTrue(profile.skipExecutionContext)
        assertEquals(setOf("SYS", "MDSYS", "XA_SYS"), profile.excludedSchemas)
    }
}
