package com.dbx.agent;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommonJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyDefaultsAndModels() {
        ConnectParams params = new ConnectParams("localhost", 5432, "demo", "user", "secret", "ssl=false", "");
        assertEquals("localhost", params.getHost());
        assertEquals("ssl=false", params.getUrl_params());

        TableInfo table = new TableInfo("orders", "TABLE");
        assertEquals("TABLE", table.getTable_type());

        QueryResult result = new QueryResult(
            Collections.singletonList("id"),
            Collections.singletonList(Collections.singletonList(1)),
            2L,
            3L
        );
        assertEquals(2L, result.getAffected_rows());
        assertEquals(3L, result.getExecution_time_ms());
        assertFalse(result.getTruncated());

        QueryPageResult page = new QueryPageResult(
            Collections.singletonList("id"),
            Collections.emptyList(),
            0L,
            1L,
            false,
            "session-1",
            true
        );
        assertEquals("session-1", page.getSession_id());
        assertEquals(true, page.getHas_more());

        assertEquals(JdbcExecutor.DEFAULT_MAX_ROWS, new ExecuteQueryOptions().getMaxRows());
        assertEquals(100, new QueryPageOptions().getPageSize());
        assertNotNull(JdbcExecutor.INSTANCE);
    }

    @Test
    void exposesDatabaseAgentDefaultMethodsToJavaImplementors() {
        DatabaseAgent agent = new MinimalAgent();

        assertEquals(1, agent.listObjects("public").size());
        assertThrows(UnsupportedOperationException.class, () ->
            agent.getObjectSource("public", "orders", "TABLE")
        );
        assertEquals("SET SCHEMA \"public\"", agent.setSchemaSQL("public"));
        assertThrows(IllegalStateException.class, () ->
            agent.executeQueryPage("select 1", "public")
        );
        assertEquals(0L, agent.executeQuery("select 1", "public").getAffected_rows());

        String ddl = DatabaseAgent.buildTableDdl(
            "public",
            "orders",
            Collections.singletonList(new ColumnInfo("id", "integer", false, null, true)),
            Collections.singletonList(new IndexInfo("orders_name_idx", Collections.singletonList("name"), false, false)),
            Collections.singletonList(new ForeignKeyInfo("orders_customer_fk", "customer_id", "customers", "id"))
        );
        assertEquals(
            "CREATE TABLE \"public\".\"orders\" (\n" +
                "  \"id\" integer NOT NULL,\n" +
                "  PRIMARY KEY (\"id\"),\n" +
                "  CONSTRAINT \"orders_customer_fk\" FOREIGN KEY (\"customer_id\") REFERENCES \"customers\"(\"id\")\n" +
                ");\n\n" +
                "CREATE INDEX \"orders_name_idx\" ON \"public\".\"orders\" (\"name\");",
            ddl
        );
    }

    @Test
    void executesTransactionsOneByOneWhenJdbcDriverDoesNotSupportTransactions() {
        List<String> calls = new ArrayList<>();
        DatabaseAgent agent = new TransactionAgent(nonTransactionalConnection(calls));

        QueryResult result = agent.executeTransaction(Arrays.asList("UPDATE A SET ID = 1", "UPDATE B SET ID = 2"), "APP");

        assertEquals(2L, result.getAffected_rows());
        assertEquals(
            Arrays.asList("supportsTransactions", "execute:SET SCHEMA \"APP\"", "executeUpdate:UPDATE A SET ID = 1", "executeUpdate:UPDATE B SET ID = 2"),
            calls
        );
    }

    @Test
    void buildsTableDdlWithoutSchemaQualifierWhenSchemaIsBlank() {
        String ddl = DatabaseAgent.buildTableDdl(
            "",
            "orders",
            Collections.singletonList(new ColumnInfo("id", "integer", false, null, true)),
            Collections.emptyList(),
            Collections.emptyList()
        );

        assertEquals(
                "CREATE TABLE \"orders\" (\n" +
                "  \"id\" integer NOT NULL,\n" +
                "  PRIMARY KEY (\"id\")\n" +
                ");\n",
            ddl
        );
    }

    private static class MinimalAgent implements DatabaseAgent {
        @Override
        public void connect(ConnectParams params) {
        }

        @Override
        public boolean testConnection(ConnectParams params) {
            return true;
        }

        @Override
        public List<DatabaseInfo> listDatabases() {
            return Collections.emptyList();
        }

        @Override
        public List<String> listSchemas() {
            return Collections.singletonList("public");
        }

        @Override
        public List<TableInfo> listTables(String schema) {
            return Collections.singletonList(new TableInfo("orders", "TABLE"));
        }

        @Override
        public List<ColumnInfo> getColumns(String schema, String table) {
            return Arrays.asList(
                new ColumnInfo("id", "integer", false, null, true),
                new ColumnInfo("name", "character varying", true, null, false, null, null, null, null, 255)
            );
        }

        @Override
        public List<IndexInfo> listIndexes(String schema, String table) {
            return Collections.emptyList();
        }

        @Override
        public List<ForeignKeyInfo> listForeignKeys(String schema, String table) {
            return Collections.emptyList();
        }

        @Override
        public List<TriggerInfo> listTriggers(String schema, String table) {
            return Collections.emptyList();
        }

        @Override
        public QueryResult executeQuery(String sql, String schema, ExecuteQueryOptions options) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0L, 0L);
        }

        @Override
        public void disconnect() {
        }

        @Override
        public Connection getConnection() {
            return null;
        }
    }

    private static final class TransactionAgent extends MinimalAgent {
        private final Connection connection;

        private TransactionAgent(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }
    }

    private static Connection nonTransactionalConnection(List<String> calls) {
        return proxy(Connection.class, (method, args) -> {
            String name = method.getName();
            if ("getMetaData".equals(name)) {
                return proxy(java.sql.DatabaseMetaData.class, (metaMethod, metaArgs) -> {
                    if ("supportsTransactions".equals(metaMethod.getName())) {
                        calls.add("supportsTransactions");
                        return false;
                    }
                    return defaultValue(metaMethod.getReturnType());
                });
            }
            if ("createStatement".equals(name)) {
                return proxy(java.sql.Statement.class, (stmtMethod, stmtArgs) -> {
                    if ("execute".equals(stmtMethod.getName())) {
                        calls.add("execute:" + stmtArgs[0]);
                        return false;
                    }
                    if ("executeUpdate".equals(stmtMethod.getName())) {
                        calls.add("executeUpdate:" + stmtArgs[0]);
                        return 1;
                    }
                    return defaultValue(stmtMethod.getReturnType());
                });
            }
            if ("setAutoCommit".equals(name) || "commit".equals(name) || "rollback".equals(name)) {
                calls.add(name);
                return null;
            }
            if ("getAutoCommit".equals(name)) {
                return true;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        InvocationHandler invocationHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return handler.handle(method, args == null ? new Object[0] : args);
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler));
    }

    private static Object defaultValue(Class<?> type) {
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Integer.TYPE.equals(type)) {
            return 0;
        }
        if (Long.TYPE.equals(type)) {
            return 0L;
        }
        return null;
    }

    private interface MethodHandler {
        Object handle(Method method, Object[] args);
    }
}
