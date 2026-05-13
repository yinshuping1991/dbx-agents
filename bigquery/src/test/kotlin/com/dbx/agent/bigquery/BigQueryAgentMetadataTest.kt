package com.dbx.agent.bigquery

import com.dbx.agent.test.JdbcMetadataSqlFake
import com.dbx.agent.test.setPrivateConnection
import kotlin.test.Test
import kotlin.test.assertTrue

class BigQueryAgentMetadataTest {
    @Test
    fun `quotes schema identifiers in metadata SQL`() {
        val agent = BigQueryAgent()
        val fake = JdbcMetadataSqlFake.connection()
        setPrivateConnection(agent, fake)

        agent.listTables("bad`schema")
        agent.getColumns("bad`schema", "sample")

        assertTrue(
            JdbcMetadataSqlFake.statements.any {
                it.contains("FROM `bad``schema`.INFORMATION_SCHEMA.TABLES")
            }
        )
        assertTrue(
            JdbcMetadataSqlFake.statements.any {
                it.contains("FROM `bad``schema`.INFORMATION_SCHEMA.COLUMNS")
            }
        )
    }
}
