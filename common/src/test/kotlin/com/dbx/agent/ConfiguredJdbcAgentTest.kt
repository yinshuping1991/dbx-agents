package com.dbx.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfiguredJdbcAgentTest {
    @Test
    fun `builds jdbc url from profile template and url params`() {
        val profile = JdbcAgentProfile(
            driverClass = "com.example.Driver",
            urlTemplate = "jdbc:example://{host}:{port}/{database}",
        )

        val url = profile.buildUrl(
            ConnectParams(
                host = "127.0.0.1",
                port = 1234,
                database = "demo",
                url_params = "ssl=false",
            )
        )

        assertEquals("jdbc:example://127.0.0.1:1234/demo?ssl=false", url)
    }

    @Test
    fun `uses explicit connection string before template`() {
        val profile = JdbcAgentProfile(
            driverClass = "com.example.Driver",
            urlTemplate = "jdbc:example://{host}:{port}/{database}",
        )

        val url = profile.buildUrl(
            ConnectParams(
                connection_string = "jdbc:example://server:4321/prod",
                host = "127.0.0.1",
                port = 1234,
                database = "demo",
            )
        )

        assertEquals("jdbc:example://server:4321/prod", url)
    }

    @Test
    fun `can disable schema switching for drivers that do not support context changes`() {
        val agent = object : ConfiguredJdbcAgent(
            JdbcAgentProfile(
                driverClass = "com.example.Driver",
                urlTemplate = "jdbc:example://{host}:{port}/{database}",
                skipExecutionContext = true,
            )
        ) {}

        assertEquals("", agent.setSchemaSQL("APP"))
    }
}
