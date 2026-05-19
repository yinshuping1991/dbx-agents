package com.dbx.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

public final class JsonRpcServer {
    private final DatabaseAgent agent;
    private final Gson gson = new Gson();

    public JsonRpcServer(DatabaseAgent agent) {
        this.agent = agent;
    }

    public void run() {
        System.out.println("{\"ready\":true}");
        System.out.flush();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String response = handleRequest(line);
                System.out.println(response);
                System.out.flush();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String handleRequest(String line) {
        JsonObject req = JsonParser.parseString(line).getAsJsonObject();
        JsonElement id = req.get("id");
        String method = req.get("method").getAsString();
        JsonObject params = paramsObject(req);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);

        try {
            Object result = dispatch(method, params);
            response.add("result", gson.toJsonTree(result));
            return gson.toJson(response);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("code", -1);
            error.addProperty("message", e.getMessage() == null ? "Unknown error" : e.getMessage());
            response.add("error", error);
            return gson.toJson(response);
        }
    }

    private Object dispatch(String method, JsonObject params) throws Exception {
        if (AgentProtocol.METHOD_HANDSHAKE.equals(method)) {
            return AgentProtocol.handshakeResult();
        }
        if ("connect".equals(method)) {
            agent.connect(gson.fromJson(params, ConnectParams.class));
            return Collections.singletonMap("ok", true);
        }
        if ("test_connection".equals(method)) {
            if (!agent.testConnection(gson.fromJson(params, ConnectParams.class))) {
                throw new RuntimeException("Connection failed");
            }
            return Collections.singletonMap("ok", true);
        }
        if ("list_databases".equals(method)) {
            return agent.listDatabases();
        }
        if ("list_schemas".equals(method)) {
            String database = stringOrNull(params, "database");
            if (database != null && !database.trim().isEmpty() && agent.getConnection() != null) {
                agent.getConnection().setCatalog(database);
            }
            return agent.listSchemas();
        }
        if ("list_tables".equals(method)) {
            return agent.listTables(params.get("schema").getAsString());
        }
        if ("list_objects".equals(method)) {
            return agent.listObjects(params.get("schema").getAsString());
        }
        if ("get_object_source".equals(method)) {
            return agent.getObjectSource(
                params.get("schema").getAsString(),
                params.get("name").getAsString(),
                params.get("object_type").getAsString()
            );
        }
        if ("get_table_ddl".equals(method)) {
            return agent.getTableDdl(params.get("schema").getAsString(), params.get("table").getAsString());
        }
        if ("get_columns".equals(method)) {
            return agent.getColumns(params.get("schema").getAsString(), params.get("table").getAsString());
        }
        if ("list_indexes".equals(method)) {
            return agent.listIndexes(params.get("schema").getAsString(), params.get("table").getAsString());
        }
        if ("list_foreign_keys".equals(method)) {
            return agent.listForeignKeys(params.get("schema").getAsString(), params.get("table").getAsString());
        }
        if ("list_triggers".equals(method)) {
            return agent.listTriggers(params.get("schema").getAsString(), params.get("table").getAsString());
        }
        if ("execute_query".equals(method)) {
            return agent.executeQuery(
                params.get("sql").getAsString(),
                stringOrNull(params, "schema"),
                new ExecuteQueryOptions(
                    intOrDefault(params, "maxRows", JdbcExecutor.DEFAULT_MAX_ROWS),
                    intOrNull(params, "fetchSize")
                )
            );
        }
        if ("execute_query_page".equals(method)) {
            return agent.executeQueryPage(
                params.get("sql").getAsString(),
                stringOrNull(params, "schema"),
                new QueryPageOptions(
                    intOrDefault(params, "pageSize", 100),
                    intOrNull(params, "fetchSize"),
                    intOrDefault(params, "maxRows", JdbcExecutor.DEFAULT_MAX_ROWS)
                )
            );
        }
        if ("fetch_query_page".equals(method)) {
            return agent.fetchQueryPage(
                params.get("sessionId").getAsString(),
                intOrDefault(params, "pageSize", 100)
            );
        }
        if ("close_query_session".equals(method)) {
            return agent.closeQuerySession(params.get("sessionId").getAsString());
        }
        if ("execute_transaction".equals(method)) {
            Type statementsType = new TypeToken<List<String>>() {}.getType();
            List<String> statements = gson.fromJson(params.get("statements"), statementsType);
            return agent.executeTransaction(statements, stringOrNull(params, "schema"));
        }
        if ("disconnect".equals(method)) {
            JdbcExecutor.INSTANCE.closeAllQuerySessions();
            agent.disconnect();
            return Collections.singletonMap("ok", true);
        }
        if ("shutdown".equals(method)) {
            JdbcExecutor.INSTANCE.closeAllQuerySessions();
            agent.disconnect();
            System.exit(0);
            return Collections.singletonMap("ok", true);
        }
        throw new IllegalArgumentException("Unknown method: " + method);
    }

    private static JsonObject paramsObject(JsonObject req) {
        JsonElement params = req.get("params");
        if (params == null || params instanceof JsonNull || !params.isJsonObject()) {
            return new JsonObject();
        }
        return params.getAsJsonObject();
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private static Integer intOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element instanceof JsonNull) {
            return null;
        }
        return element.getAsInt();
    }

    private static int intOrDefault(JsonObject object, String key, int defaultValue) {
        Integer value = intOrNull(object, key);
        return value == null ? defaultValue : value;
    }

}
