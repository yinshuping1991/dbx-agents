package com.dbx.agent.test

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement

object JdbcMetadataSqlFake {
    val statements = mutableListOf<String>()

    fun connection(): Connection {
        statements.clear()
        return proxy(Connection::class.java) { method, args ->
            when (method.name) {
                "createStatement" -> statement()
                "prepareStatement" -> {
                    statements.add(args?.getOrNull(0) as String)
                    preparedStatement()
                }
                "getAutoCommit" -> true
                "setAutoCommit", "commit", "rollback", "close" -> null
                "isClosed" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun statement(): Statement {
        val resultSet = emptyResultSet()
        return proxy(Statement::class.java) { method, args ->
            when (method.name) {
                "execute", "executeQuery" -> {
                    statements.add(args?.getOrNull(0) as String)
                    if (method.name == "execute") false else resultSet
                }
                "getResultSet" -> resultSet
                "getUpdateCount" -> 0
                "setMaxRows", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun preparedStatement(): PreparedStatement {
        val resultSet = emptyResultSet()
        return proxy(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "execute", "executeQuery" -> if (method.name == "execute") false else resultSet
                "getResultSet" -> resultSet
                "getUpdateCount" -> 0
                "setString", "setMaxRows", "close" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun emptyResultSet(): ResultSet {
        val metadata = proxy(ResultSetMetaData::class.java) { method, _ ->
            when (method.name) {
                "getColumnCount" -> 0
                else -> defaultValue(method.returnType)
            }
        }
        return proxy(ResultSet::class.java) { method, _ ->
            when (method.name) {
                "next" -> false
                "getMetaData" -> metadata
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
