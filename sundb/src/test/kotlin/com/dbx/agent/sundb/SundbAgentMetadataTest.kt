package com.dbx.agent.sundb

import com.dbx.agent.test.JdbcMetadataSqlFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class SundbAgentMetadataTest {
    @Test
    fun `quotes schema and table identifiers in index metadata SQL`() {
        val agent = SundbAgent()
        val fake = JdbcMetadataSqlFake.connection()
        setPrivateConnection(agent, fake)

        agent.listIndexes("bad`schema", "bad`table")

        assertEquals(
            listOf("SHOW INDEX FROM `bad``table` FROM `bad``schema`"),
            JdbcMetadataSqlFake.statements,
        )
    }
}
