package com.dbx.agent.informix

import com.dbx.agent.ConnectParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InformixAgentTest {
    @Test
    fun `builds JDBC URL with explicit Informix server and locale parameters`() {
        val url = InformixAgent.buildJdbcUrl(
            ConnectParams(
                host = "172.26.128.159",
                port = 20013,
                database = "testdb",
                url_params = "INFORMIXSERVER=informix;CLIENT_LOCALE=en_US.utf8;DB_LOCALE=en_US.utf8",
            )
        )

        assertEquals(
            "jdbc:informix-sqli://172.26.128.159:20013/testdb:INFORMIXSERVER=informix;CLIENT_LOCALE=en_US.utf8;DB_LOCALE=en_US.utf8",
            url,
        )
    }

    @Test
    fun `falls back to host as Informix server when no explicit server is configured`() {
        val url = InformixAgent.buildJdbcUrl(
            ConnectParams(
                host = "informix-host",
                port = 9088,
                database = "sysmaster",
            )
        )

        assertEquals(
            "jdbc:informix-sqli://informix-host:9088/sysmaster:INFORMIXSERVER=informix-host",
            url,
        )
    }

    @Test
    fun `extracts primary key column numbers from Informix index parts`() {
        assertEquals(setOf(1, 3, 5), InformixAgent.primaryKeyColumnNumbers(listOf(1, -3, 0, 5, null)))
    }

    @Test
    fun `lists databases from sysmaster catalog`() {
        assertEquals("SELECT name FROM sysmaster:sysdatabases ORDER BY name", InformixAgent.databaseCatalogSql())
    }
}
