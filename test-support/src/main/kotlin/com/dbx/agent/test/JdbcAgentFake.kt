package com.dbx.agent.test

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

object JdbcAgentFake {
    val calls = mutableListOf<String>()

    fun connection(): Connection {
        calls.clear()
        val statement = statement()
        return proxy(Connection::class.java) { method, _ ->
            when (method.name) {
                "createStatement" -> statement
                "getAutoCommit" -> true
                "setAutoCommit", "commit", "rollback", "close" -> null
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun statement(): Statement {
        val resultSet = resultSet()
        return proxy(Statement::class.java) { method, _ ->
            when (method.name) {
                "execute" -> {
                    calls.add("execute")
                    true
                }
                "executeQuery" -> {
                    calls.add("executeQuery")
                    resultSet
                }
                "executeUpdate" -> {
                    calls.add("executeUpdate")
                    0
                }
                "getResultSet" -> resultSet
                "getUpdateCount" -> 0
                "setMaxRows", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun resultSet(): ResultSet {
        var index = -1
        val metadata = metadata()
        return proxy(ResultSet::class.java) { method, _ ->
            when (method.name) {
                "next" -> {
                    index += 1
                    index == 0
                }
                "getMetaData" -> metadata
                "getObject" -> "row-value"
                "getString" -> "row-value"
                "wasNull" -> false
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun metadata(): ResultSetMetaData {
        return proxy(ResultSetMetaData::class.java) { method, _ ->
            when (method.name) {
                "getColumnCount" -> 1
                "getColumnLabel" -> "VALUE"
                "getColumnType" -> java.sql.Types.VARCHAR
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

fun setPrivateConnection(target: Any, connection: Connection) {
    val field = target::class.java.getDeclaredField("connection")
    field.isAccessible = true
    field.set(target, connection)
}
