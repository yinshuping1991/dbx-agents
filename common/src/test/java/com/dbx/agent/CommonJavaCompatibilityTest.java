package com.dbx.agent;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Arrays;
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

    private static final class MinimalAgent implements DatabaseAgent {
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
}
