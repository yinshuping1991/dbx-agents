package com.dbx.agent.hive

import com.dbx.agent.test.JdbcMetadataSqlFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class HiveAgentMetadataTest {
    @Test
    fun `quotes schema and table identifiers in metadata SQL`() {
        val agent = HiveAgent()
        val fake = JdbcMetadataSqlFake.connection()
        setPrivateConnection(agent, fake)

        agent.listTables("bad`schema")
        agent.getColumns("bad`schema", "bad`table")

        assertEquals(
            listOf(
                "USE `bad``schema`",
                "SHOW TABLES",
                "USE `bad``schema`",
                "DESCRIBE `bad``table`",
            ),
            JdbcMetadataSqlFake.statements,
        )
    }
}
