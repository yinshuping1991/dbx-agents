package com.dbx.agent.mongodb;

import com.dbx.agent.AgentProtocol;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;

public final class MongoAgent {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static MongoClient client;

    private MongoAgent() {
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element instanceof JsonNull ? null : element.getAsString();
    }

    private static Object connect(JsonObject params) {
        JsonObject connObj = params.has("connection") && params.get("connection").isJsonObject()
            ? params.getAsJsonObject("connection")
            : params;
        String host = connObj.has("host") ? connObj.get("host").getAsString() : "127.0.0.1";
        int port = connObj.has("port") ? connObj.get("port").getAsInt() : 27017;
        String username = coalesce(stringOrNull(connObj, "username"));
        String password = coalesce(stringOrNull(connObj, "password"));
        String database = defaultString(stringOrNull(connObj, "database"), "admin");
        String authDatabase = authenticationDatabase(connObj);
        String connectionString = stringOrNull(connObj, "connection_string");

        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        if (connectionString != null && !connectionString.isBlank()) {
            builder.applyConnectionString(new ConnectionString(connectionString));
        } else {
            builder.applyToClusterSettings(settings -> settings.hosts(Collections.singletonList(new ServerAddress(host, port))));
            if (!username.isBlank()) {
                builder.credential(MongoCredential.createCredential(username, authDatabase, password.toCharArray()));
            }
        }

        if (client != null) {
            client.close();
        }
        client = MongoClients.create(builder.build());
        client.getDatabase(database).runCommand(new Document("ping", 1));
        return Collections.singletonMap("ok", true);
    }

    static String authenticationDatabase(JsonObject connObj) {
        String authSource = urlParam(stringOrNull(connObj, "url_params"), "authSource");
        if (authSource != null && !authSource.isBlank()) {
            return authSource;
        }
        return defaultString(stringOrNull(connObj, "database"), "admin");
    }

    private static String urlParam(String urlParams, String key) {
        if (urlParams == null || urlParams.isBlank()) {
            return null;
        }
        String normalized = urlParams.startsWith("?") ? urlParams.substring(1) : urlParams;
        for (String pair : normalized.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            if (decode(parts[0]).equals(key)) {
                return parts.length > 1 ? decode(parts[1]) : "";
            }
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Object listDatabases() {
        MongoClient c = requireClient();
        List<Map<String, String>> result = new ArrayList<>();
        for (String name : c.listDatabaseNames()) {
            result.add(Collections.singletonMap("name", name));
        }
        return result;
    }

    private static Object listCollections(JsonObject params) {
        MongoClient c = requireClient();
        String database = params.get("database").getAsString();
        List<String> result = new ArrayList<>();
        for (String name : c.getDatabase(database).listCollectionNames()) {
            result.add(name);
        }
        return result;
    }

    private static Object findDocuments(JsonObject params) {
        MongoClient c = requireClient();
        String database = params.get("database").getAsString();
        String collection = params.get("collection").getAsString();
        long skip = params.has("skip") ? params.get("skip").getAsLong() : 0;
        int limit = params.has("limit") ? params.get("limit").getAsInt() : 50;
        String filterJson = stringOrNull(params, "filter");
        String sortJson = stringOrNull(params, "sort");

        var col = c.getDatabase(database).getCollection(collection);
        Document filterDoc = filterJson != null && !filterJson.isBlank() ? Document.parse(filterJson) : new Document();
        long total = col.countDocuments(filterDoc);

        var iterable = col.find(filterDoc).skip((int) skip).limit(limit);
        if (sortJson != null && !sortJson.isBlank()) {
            iterable = iterable.sort(Document.parse(sortJson));
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        for (Document document : iterable) {
            documents.add(bsonToJson(document));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", documents);
        result.put("total", total);
        return result;
    }

    private static Object insertDocument(JsonObject params) {
        MongoClient c = requireClient();
        String database = params.get("database").getAsString();
        String collection = params.get("collection").getAsString();
        String docJson = params.get("doc_json").getAsString();

        Document doc = Document.parse(docJson);
        var result = c.getDatabase(database).getCollection(collection).insertOne(doc);
        Object insertedId = result.getInsertedId() == null || !result.getInsertedId().isObjectId()
            ? null
            : result.getInsertedId().asObjectId().getValue().toHexString();
        return Collections.singletonMap("inserted_id", insertedId);
    }

    private static Object parseId(String id) {
        try {
            return new ObjectId(id);
        } catch (Exception e) {
            return id;
        }
    }

    private static Object updateDocument(JsonObject params) {
        MongoClient c = requireClient();
        String database = params.get("database").getAsString();
        String collection = params.get("collection").getAsString();
        String id = params.get("id").getAsString();
        String docJson = params.get("doc_json").getAsString();

        var col = c.getDatabase(database).getCollection(collection);
        Document newDoc = Document.parse(docJson);
        newDoc.remove("_id");
        var result = col.replaceOne(new Document("_id", parseId(id)), newDoc);
        return Collections.singletonMap("modified_count", result.getModifiedCount());
    }

    private static Object deleteDocument(JsonObject params) {
        MongoClient c = requireClient();
        String database = params.get("database").getAsString();
        String collection = params.get("collection").getAsString();
        String id = params.get("id").getAsString();

        var col = c.getDatabase(database).getCollection(collection);
        var result = col.deleteOne(new Document("_id", parseId(id)));
        return Collections.singletonMap("deleted_count", result.getDeletedCount());
    }

    private static Map<String, Object> bsonToJson(Document doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            result.put(entry.getKey(), convertValue(entry.getValue()));
        }
        return result;
    }

    private static Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId objectId) {
            return objectId.toHexString();
        }
        if (value instanceof Document document) {
            return bsonToJson(document);
        }
        if (value instanceof List<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                result.add(convertValue(item));
            }
            return result;
        }
        if (value instanceof java.util.Date date) {
            Instant instant = Instant.ofEpochMilli(date.getTime());
            return DATE_FORMAT.format(instant);
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long ||
            value instanceof Double || value instanceof String) {
            return value;
        }
        return value.toString();
    }

    private static Object dispatch(String method, JsonObject params) {
        return switch (method) {
            case AgentProtocol.METHOD_HANDSHAKE -> AgentProtocol.handshakeResult();
            case "connect" -> connect(params);
            case "list_databases" -> listDatabases();
            case "list_collections" -> listCollections(params);
            case "find_documents" -> findDocuments(params);
            case "insert_document" -> insertDocument(params);
            case "update_document" -> updateDocument(params);
            case "delete_document" -> deleteDocument(params);
            case "disconnect", "shutdown" -> {
                if (client != null) {
                    client.close();
                    client = null;
                }
                if ("shutdown".equals(method)) {
                    System.exit(0);
                }
                yield Collections.singletonMap("ok", true);
            }
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        };
    }

    private static MongoClient requireClient() {
        if (client == null) {
            throw new IllegalStateException("Not connected");
        }
        return client;
    }

    private static String coalesce(String value) {
        return value == null ? "" : value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    static String handleRequest(String line) {
        JsonObject req = JsonParser.parseString(line).getAsJsonObject();
        JsonElement id = req.get("id");
        String method = req.get("method").getAsString();
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
            ? req.getAsJsonObject("params")
            : new JsonObject();

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);

        try {
            Object result = dispatch(method, params);
            response.add("result", GSON.toJsonTree(result));
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", -1);
            error.addProperty("message", e.getMessage() == null ? "Unknown error" : e.getMessage());
            response.add("error", error);
        }

        return GSON.toJson(response);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("{\"ready\":true}");
        System.out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }

            System.out.println(handleRequest(line));
            System.out.flush();
        }
    }
}
