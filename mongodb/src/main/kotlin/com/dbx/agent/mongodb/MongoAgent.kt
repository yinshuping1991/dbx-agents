package com.dbx.agent.mongodb

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.ConnectionString
import com.mongodb.MongoCredential
import org.bson.Document
import org.bson.types.ObjectId
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val gson = Gson()
private var client: MongoClient? = null

private fun JsonObject.stringOrNull(key: String): String? {
    val el = get(key) ?: return null
    return if (el is JsonNull) null else el.asString
}

private fun connect(params: JsonObject): Any {
    val connObj = params.getAsJsonObject("connection") ?: params
    val host = connObj.get("host")?.asString ?: "127.0.0.1"
    val port = connObj.get("port")?.asInt ?: 27017
    val username = connObj.stringOrNull("username") ?: ""
    val password = connObj.stringOrNull("password") ?: ""
    val database = connObj.stringOrNull("database") ?: "admin"
    val connectionString = connObj.stringOrNull("connection_string")

    val builder = MongoClientSettings.builder()
    if (!connectionString.isNullOrBlank()) {
        builder.applyConnectionString(ConnectionString(connectionString))
    } else {
        builder.applyToClusterSettings { it.hosts(listOf(ServerAddress(host, port))) }
        if (username.isNotBlank()) {
            builder.credential(
                MongoCredential.createCredential(username, database, password.toCharArray())
            )
        }
    }

    client?.close()
    client = MongoClients.create(builder.build())
    client!!.getDatabase(database).runCommand(Document("ping", 1))
    return mapOf("ok" to true)
}

private fun listDatabases(): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    return c.listDatabaseNames().toList().map { mapOf("name" to it) }
}

private fun listCollections(params: JsonObject): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    val database = params.get("database").asString
    return c.getDatabase(database).listCollectionNames().toList()
}

private fun findDocuments(params: JsonObject): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    val database = params.get("database").asString
    val collection = params.get("collection").asString
    val skip = params.get("skip")?.asLong ?: 0
    val limit = params.get("limit")?.asInt ?: 50
    val filterJson = params.stringOrNull("filter")
    val sortJson = params.stringOrNull("sort")

    val col = c.getDatabase(database).getCollection(collection)

    val filterDoc = if (!filterJson.isNullOrBlank()) Document.parse(filterJson) else Document()
    val total = col.countDocuments(filterDoc)

    var iterable = col.find(filterDoc).skip(skip.toInt()).limit(limit)
    if (!sortJson.isNullOrBlank()) {
        iterable = iterable.sort(Document.parse(sortJson))
    }

    val documents = iterable.map { bsonToJson(it) }.toList()
    return mapOf("documents" to documents, "total" to total)
}

private fun insertDocument(params: JsonObject): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    val database = params.get("database").asString
    val collection = params.get("collection").asString
    val docJson = params.get("doc_json").asString

    val doc = Document.parse(docJson)
    val result = c.getDatabase(database).getCollection(collection).insertOne(doc)
    return mapOf("inserted_id" to result.insertedId?.asObjectId()?.value?.toHexString())
}

private fun parseId(id: String): Any {
    return try { ObjectId(id) } catch (_: Exception) { id }
}

private fun updateDocument(params: JsonObject): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    val database = params.get("database").asString
    val collection = params.get("collection").asString
    val id = params.get("id").asString
    val docJson = params.get("doc_json").asString

    val col = c.getDatabase(database).getCollection(collection)
    val newDoc = Document.parse(docJson)
    newDoc.remove("_id")
    val result = col.replaceOne(Document("_id", parseId(id)), newDoc)
    return mapOf("modified_count" to result.modifiedCount)
}

private fun deleteDocument(params: JsonObject): Any {
    val c = client ?: throw IllegalStateException("Not connected")
    val database = params.get("database").asString
    val collection = params.get("collection").asString
    val id = params.get("id").asString

    val col = c.getDatabase(database).getCollection(collection)
    val result = col.deleteOne(Document("_id", parseId(id)))
    return mapOf("deleted_count" to result.deletedCount)
}

private fun bsonToJson(doc: Document): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for ((key, value) in doc) {
        result[key] = convertValue(value)
    }
    return result
}

private fun convertValue(value: Any?): Any? {
    return when (value) {
        null -> null
        is ObjectId -> value.toHexString()
        is Document -> bsonToJson(value)
        is List<*> -> value.map { convertValue(it) }
        is java.util.Date -> {
            val instant = Instant.ofEpochMilli(value.time)
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(instant)
        }
        is Boolean, is Int, is Long, is Double, is String -> value
        else -> value.toString()
    }
}

private fun dispatch(method: String, params: JsonObject): Any {
    return when (method) {
        "connect" -> connect(params)
        "list_databases" -> listDatabases()
        "list_collections" -> listCollections(params)
        "find_documents" -> findDocuments(params)
        "insert_document" -> insertDocument(params)
        "update_document" -> updateDocument(params)
        "delete_document" -> deleteDocument(params)
        "disconnect", "shutdown" -> {
            client?.close()
            client = null
            if (method == "shutdown") System.exit(0)
            mapOf("ok" to true)
        }
        else -> throw IllegalArgumentException("Unknown method: $method")
    }
}

fun main() {
    println("""{"ready":true}""")
    System.out.flush()

    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        val line = reader.readLine() ?: break
        val req = JsonParser.parseString(line).asJsonObject
        val id = req.get("id")
        val method = req.get("method").asString
        val params = req.getAsJsonObject("params") ?: JsonObject()

        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        response.add("id", id)

        try {
            val result = dispatch(method, params)
            response.add("result", gson.toJsonTree(result))
        } catch (e: Exception) {
            val error = JsonObject()
            error.addProperty("code", -1)
            error.addProperty("message", e.message ?: "Unknown error")
            response.add("error", error)
        }

        println(gson.toJson(response))
        System.out.flush()
    }
}
