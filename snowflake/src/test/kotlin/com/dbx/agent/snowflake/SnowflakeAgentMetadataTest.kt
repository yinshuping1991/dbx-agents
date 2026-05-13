package com.dbx.agent.snowflake

import com.dbx.agent.test.JdbcMetadataSqlFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertTrue

class SnowflakeAgentMetadataTest {
    @Test
    fun `quotes schema and table identifiers in key metadata SQL`() {
        val agent = SnowflakeAgent()
        val fake = JdbcMetadataSqlFake.connection()
        setPrivateConnection(agent, fake)

        agent.getColumns("bad\"schema", "bad\"table")
        agent.listForeignKeys("bad\"schema", "bad\"table")

        assertTrue(
            JdbcMetadataSqlFake.statements.contains(
                "SHOW PRIMARY KEYS IN TABLE \"bad\"\"schema\".\"bad\"\"table\""
            )
        )
        assertTrue(
            JdbcMetadataSqlFake.statements.contains(
                "SHOW IMPORTED KEYS IN TABLE \"bad\"\"schema\".\"bad\"\"table\""
            )
        )
    }
}
