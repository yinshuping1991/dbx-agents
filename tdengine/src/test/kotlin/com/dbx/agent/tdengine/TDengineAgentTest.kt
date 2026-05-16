package com.dbx.agent.tdengine

import com.dbx.agent.ConnectParams
import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcAgentFake
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import com.dbx.agent.test.JdbcMetadataSqlFake
import com.dbx.agent.test.setPrivateConnection
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals

class TDengineAgentExecutionTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return TDengineAgent()
    }

    override fun resultSetSql(): String = "SHOW DATABASES"
}

class TDengineAgentMetadataTest {
    @Test
    fun `builds websocket jdbc url with default port and database`() {
        val url = TDengineJdbcUrl.from(
            ConnectParams(
                host = "127.0.0.1",
                port = 0,
                database = "meters",
                username = "root",
                password = "taosdata",
            )
        )

        assertEquals("jdbc:TAOS-WS://127.0.0.1:6041/meters", url)
    }

    @Test
    fun `preserves custom websocket port and url params`() {
        val url = TDengineJdbcUrl.from(
            ConnectParams(
                host = "td.local",
                port = 6042,
                database = "",
                url_params = "timezone=UTC&charset=UTF-8",
            )
        )

        assertEquals("jdbc:TAOS-WS://td.local:6042/?timezone=UTC&charset=UTF-8", url)
    }

    @Test
    fun `uses tdengine metadata statements`() {
        val agent = TDengineAgent()
        setPrivateConnection(agent, JdbcMetadataSqlFake.connection())

        agent.listDatabases()
        agent.listTables("power")
        agent.getColumns("power", "meters")

        assertEquals(
            listOf(
                "SHOW DATABASES",
                "SHOW `power`.STABLES",
                "SHOW `power`.TABLES",
                "DESCRIBE `power`.`meters`",
            ),
            JdbcMetadataSqlFake.statements,
        )
    }

    @Test
    fun `sets database before execution when schema is provided`() {
        val agent = TDengineAgent()
        setPrivateConnection(agent, JdbcAgentFake.connection())

        agent.executeQuery("SELECT 1", "power")

        assertEquals(listOf("execute", "setMaxRows:10001", "execute"), JdbcAgentFake.calls)
    }

    @Test
    fun `decodes tdengine byte array text values`() {
        assertEquals("d1001", decodeTdengineValue("d1001".toByteArray()))
    }

    @Test
    fun `formats tdengine timestamps as sql literals`() {
        assertEquals("2026-05-16 09:35:58.123", decodeTdengineValue(Timestamp.valueOf("2026-05-16 09:35:58.123")))
    }
}
