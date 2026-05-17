package com.dbx.agent.oracle10g

import com.dbx.agent.DatabaseAgent
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest
import com.dbx.agent.test.setPrivateConnection
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.assertEquals

class Oracle10gAgentTest : JdbcFakeExecutionBehaviorTest() {
    override fun createAgent(): DatabaseAgent {
        return Oracle10gAgent()
    }

    override fun resultSetSql(): String = "CALL DBMS_XPLAN.DISPLAY_CURSOR()"

    @Test
    fun `lists tables views procedures and functions`() {
        val agent = Oracle10gAgent()
        setPrivateConnection(agent, objectListConnection())

        val objects = agent.listObjects("APP")

        assertEquals(
            listOf("TABLE", "VIEW", "PROCEDURE", "FUNCTION"),
            objects.map { it.object_type }
        )
        assertEquals(
            listOf("APP_TABLE", "APP_VIEW", "APP_PROC", "APP_FUNC"),
            objects.map { it.name }
        )
    }

    @Test
    fun `loads routine source from dbms metadata`() {
        val agent = Oracle10gAgent()
        setPrivateConnection(agent, metadataConnection {
            listOf(listOf("CREATE OR REPLACE PROCEDURE APP_PROC AS BEGIN NULL; END;"))
        })

        val source = agent.getObjectSource("APP", "APP_PROC", "PROCEDURE")

        assertEquals("APP_PROC", source.name)
        assertEquals("PROCEDURE", source.object_type)
        assertEquals("APP", source.schema)
        assertEquals("CREATE OR REPLACE PROCEDURE APP_PROC AS BEGIN NULL; END;", source.source)
    }

    private fun objectListConnection(): Connection {
        return metadataConnection { sql ->
            if (sql.contains("'PROCEDURE'")) {
                listOf(
                    listOf("APP_TABLE", "TABLE"),
                    listOf("APP_VIEW", "VIEW"),
                    listOf("APP_PROC", "PROCEDURE"),
                    listOf("APP_FUNC", "FUNCTION"),
                )
            } else {
                listOf(
                    listOf("APP_TABLE", "TABLE", ""),
                    listOf("APP_VIEW", "VIEW", ""),
                )
            }
        }
    }

    private fun metadataConnection(rowsForSql: (String) -> List<List<String>>): Connection {
        return proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "prepareStatement" -> metadataStatement(rowsForSql(args?.get(0) as String))
                "close" -> null
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun metadataStatement(rows: List<List<String>>): PreparedStatement {
        return proxy(PreparedStatement::class.java) { method, _ ->
            when (method.name) {
                "executeQuery" -> metadataResultSet(rows)
                "setString", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun metadataResultSet(rows: List<List<String>>): ResultSet {
        var index = -1
        return proxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> {
                    index += 1
                    index < rows.size
                }
                "getString" -> rows[index][(args?.get(0) as Int) - 1]
                "close" -> null
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
