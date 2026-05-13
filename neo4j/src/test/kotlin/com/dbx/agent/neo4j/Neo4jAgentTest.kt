package com.dbx.agent.neo4j

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement

class Neo4jAgentTest {

    @Test
    fun `executes Cypher writes with RETURN through Statement execute`() {
        val agent = Neo4jAgent()
        val calls = mutableListOf<String>()
        agent.setConnectionForTest(fakeConnection(calls))

        val result = agent.executeQuery("CREATE (n:Person {name: 'Ada'}) RETURN n", null)

        assertEquals(listOf("n"), result.columns)
        assertEquals(listOf(listOf("(:Person {name: Ada})")), result.rows)
        assertTrue(calls.contains("execute"))
        assertFalse(calls.contains("executeUpdate"))
    }

    @Test
    fun `executes transactions through Statement execute`() {
        val agent = Neo4jAgent()
        val calls = mutableListOf<String>()
        agent.setConnectionForTest(fakeConnection(calls))

        val result = agent.executeTransaction(
            listOf(
                "MATCH (n:Employee) WHERE elementId(n) = '4:abc:7' SET n.name = 'Grace'",
                "CREATE (n:Employee {name: 'Linus'})",
            ),
            null,
        )

        assertEquals(0, result.affected_rows)
        assertEquals(listOf("setAutoCommit:false", "execute", "execute", "commit", "setAutoCommit:true"), calls)
        assertFalse(calls.contains("executeUpdate"))
    }

    private fun Neo4jAgent.setConnectionForTest(connection: Connection) {
        val field = Neo4jAgent::class.java.getDeclaredField("connection")
        field.isAccessible = true
        field.set(this, connection)
    }

    private fun fakeConnection(calls: MutableList<String>): Connection {
        val statement = fakeStatement(calls)
        return proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "createStatement" -> statement
                "setAutoCommit" -> {
                    calls.add("setAutoCommit:${args?.get(0)}")
                    null
                }
                "getAutoCommit" -> true
                "commit" -> {
                    calls.add("commit")
                    null
                }
                "rollback" -> {
                    calls.add("rollback")
                    null
                }
                "close" -> null
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun fakeStatement(calls: MutableList<String>): Statement {
        val resultSet = fakeResultSet()
        return proxy(Statement::class.java) { method, args ->
            when (method.name) {
                "execute" -> {
                    calls.add("execute")
                    true
                }
                "executeUpdate" -> {
                    calls.add("executeUpdate")
                    throw SQLException("syntax error or access rule violation - invalid syntax")
                }
                "executeQuery" -> {
                    calls.add("executeQuery")
                    resultSet
                }
                "getResultSet" -> resultSet
                "getUpdateCount" -> 0
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun fakeResultSet(): ResultSet {
        var index = -1
        val metadata = fakeMetadata()
        return proxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> {
                    index += 1
                    index == 0
                }
                "getMetaData" -> metadata
                "getObject" -> "(:Person {name: Ada})"
                "wasNull" -> false
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun fakeMetadata(): ResultSetMetaData {
        return proxy(ResultSetMetaData::class.java) { method, args ->
            when (method.name) {
                "getColumnCount" -> 1
                "getColumnLabel" -> "n"
                else -> defaultValue(method.returnType)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T {
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}
