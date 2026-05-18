package com.dbx.agent;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class BaseDatabaseAgent implements DatabaseAgent {
    @Override
    public List<ObjectInfo> listObjects(String schema) {
        List<ObjectInfo> result = new ArrayList<>();
        for (TableInfo table : listTables(schema)) {
            result.add(new ObjectInfo(table.getName(), table.getTable_type(), schema, table.getComment()));
        }
        return result;
    }

    @Override
    public ObjectSource getObjectSource(String schema, String name, String objectType) {
        throw new UnsupportedOperationException("Object source is not supported");
    }

    @Override
    public String getTableDdl(String schema, String table) {
        List<IndexInfo> indexes;
        try {
            indexes = listIndexes(schema, table);
        } catch (RuntimeException e) {
            indexes = Collections.emptyList();
        }

        List<ForeignKeyInfo> foreignKeys;
        try {
            foreignKeys = listForeignKeys(schema, table);
        } catch (RuntimeException e) {
            foreignKeys = Collections.emptyList();
        }

        return DatabaseAgent.buildTableDdl(schema, table, getColumns(schema, table), indexes, foreignKeys);
    }

    @Override
    public QueryPageResult executeQueryPage(String sql, String schema, QueryPageOptions options) {
        Connection conn = requireConnected();
        return JdbcExecutor.INSTANCE.executePage(
            conn,
            sql,
            schema,
            this::setSchemaSQL,
            options,
            JdbcExecutor.INSTANCE::defaultResultValue
        );
    }

    @Override
    public QueryPageResult fetchQueryPage(String sessionId, int pageSize) {
        return JdbcExecutor.INSTANCE.fetchPage(sessionId, pageSize);
    }

    @Override
    public boolean closeQuerySession(String sessionId) {
        return JdbcExecutor.INSTANCE.closeQuerySession(sessionId);
    }

    @Override
    public QueryResult executeTransaction(List<String> statements, String schema) {
        return unchecked(() -> {
            Connection conn = requireConnected();
            boolean savedAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            long start = System.currentTimeMillis();
            try {
                if (schema != null && !schema.trim().isEmpty()) {
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        stmt.execute(setSchemaSQL(schema));
                    }
                }

                long totalAffected = 0;
                for (String statement : statements) {
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        totalAffected += stmt.executeUpdate(trimSql(statement));
                    }
                }
                conn.commit();
                return new QueryResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    totalAffected,
                    System.currentTimeMillis() - start,
                    false
                );
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(savedAutoCommit);
            }
        });
    }

    protected Connection requireConnected() {
        Connection conn = getConnection();
        if (conn == null) {
            throw new IllegalStateException("Not connected");
        }
        return conn;
    }

    private static String trimSql(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    protected static <T> T unchecked(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static void uncheckedVoid(ThrowingRunnable runnable) {
        unchecked(() -> {
            runnable.run();
            return null;
        });
    }

    protected interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    protected interface ThrowingRunnable {
        void run() throws Exception;
    }
}
