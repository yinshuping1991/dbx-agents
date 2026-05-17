package com.dbx.agent.access

import com.dbx.agent.ConnectParams
import com.dbx.agent.DatabaseAgent
import com.dbx.agent.QueryPageOptions
import com.dbx.agent.test.JdbcConnectedAgentTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessAgentTest : JdbcConnectedAgentTest() {
    @field:TempDir
    lateinit var tempDir: Path

    override fun createConnectedAgent(databaseName: String): DatabaseAgent {
        val file = tempDir.resolve("$databaseName.accdb")
        return AccessAgent().apply {
            connect(ConnectParams(database = file.toString()))
        }
    }

    @Test
    fun `connects to an access file and returns table metadata`() {
        withAgent("metadata") { agent ->
            agent.executeQuery("CREATE TABLE People (ID COUNTER PRIMARY KEY, Name TEXT(64), Active YESNO)", null)
            agent.executeQuery("INSERT INTO People (Name, Active) VALUES ('Ada', TRUE)", null)

            val tables = agent.listTables("")
            val columns = agent.getColumns("", "People")
            val result = agent.executeQuery("SELECT Name, Active FROM People", null)

            assertTrue(tables.any { it.name == "People" })
            assertEquals(listOf("ID", "Name", "Active"), columns.map { it.name })
            assertTrue(columns.first { it.name == "ID" }.is_primary_key)
            assertEquals(listOf("Name", "Active"), result.columns)
            assertEquals("Ada", result.rows[0][0])
            assertEquals(true, result.rows[0][1])
        }
    }

    @Test
    fun `returns empty schema list for access files`() {
        withAgent("schemas") { agent ->
            assertEquals(emptyList(), agent.listSchemas())
            assertFalse(agent.testConnection(ConnectParams(database = tempDir.resolve("missing.accdb").toString())))
        }
    }

    @Test
    fun `supports limit offset sql and cursor result pages`() {
        withAgent("pages") { agent ->
            agent.executeQuery("CREATE TABLE Items (ID COUNTER PRIMARY KEY, Value INTEGER)", null)
            for (i in 1..5) {
                agent.executeQuery("INSERT INTO Items (Value) VALUES ($i)", null)
            }

            val limited = agent.executeQuery("SELECT [Value] FROM [Items] ORDER BY [ID] LIMIT 2 OFFSET 1", null)
            val firstPage = agent.executeQueryPage(
                "SELECT Value FROM Items ORDER BY ID",
                null,
                QueryPageOptions(pageSize = 2, maxRows = 5),
            )
            val secondPage = agent.fetchQueryPage(firstPage.session_id ?: error("expected session id"), 2)

            assertEquals(listOf(2, 3), limited.rows.map { it[0] })
            assertEquals(listOf(1, 2), firstPage.rows.map { it[0] })
            assertEquals(true, firstPage.has_more)
            assertEquals(listOf(3, 4), secondPage.rows.map { it[0] })
        }
    }

}
