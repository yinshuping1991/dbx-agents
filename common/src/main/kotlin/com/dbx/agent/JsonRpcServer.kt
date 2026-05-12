package com.dbx.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader

class JsonRpcServer(private val agent: DatabaseAgent) {
    private val gson = Gson()

    fun run() {
        println("""{"ready":true}""")
        System.out.flush()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val response = handleRequest(line)
            println(response)
            System.out.flush()
        }
    }

    private fun handleRequest(line: String): String {
        val req = JsonParser.parseString(line).asJsonObject
        val id = req.get("id")
        val method = req.get("method").asString
        val params = req.getAsJsonObject("params") ?: JsonObject()

        return try {
            val result = dispatch(method, params)
            """{"jsonrpc":"2.0","id":${id},"result":${gson.toJson(result)}}"""
        } catch (e: Exception) {
            val msg = e.message?.replace("\"", "\\\"") ?: "Unknown error"
            """{"jsonrpc":"2.0","id":${id},"error":{"code":-1,"message":"${msg}"}}"""
        }
    }

    private fun dispatch(method: String, params: JsonObject): Any {
        return when (method) {
            "connect" -> {
                agent.connect(gson.fromJson(params, ConnectParams::class.java))
                mapOf("ok" to true)
            }
            "test_connection" -> {
                agent.testConnection(gson.fromJson(params, ConnectParams::class.java))
                mapOf("ok" to true)
            }
            "list_databases" -> agent.listDatabases()
            "list_schemas" -> agent.listSchemas()
            "list_tables" -> agent.listTables(params.get("schema").asString)
            "get_columns" -> agent.getColumns(
                params.get("schema").asString,
                params.get("table").asString
            )
            "list_indexes" -> agent.listIndexes(
                params.get("schema").asString,
                params.get("table").asString
            )
            "list_foreign_keys" -> agent.listForeignKeys(
                params.get("schema").asString,
                params.get("table").asString
            )
            "list_triggers" -> agent.listTriggers(
                params.get("schema").asString,
                params.get("table").asString
            )
            "execute_query" -> agent.executeQuery(
                params.get("sql").asString,
                params.get("schema")?.asString
            )
            "disconnect" -> {
                agent.disconnect()
                mapOf("ok" to true)
            }
            "shutdown" -> {
                agent.disconnect()
                System.exit(0)
                mapOf("ok" to true)
            }
            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }
}
