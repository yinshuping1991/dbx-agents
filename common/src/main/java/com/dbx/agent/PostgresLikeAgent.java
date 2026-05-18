package com.dbx.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public abstract class PostgresLikeAgent implements DatabaseAgent {
    private final PostgresLikeAgentProfile profile;
    private Connection connection;

    protected PostgresLikeAgent(PostgresLikeAgentProfile profile) {
        this.profile = profile;
    }

    public PostgresLikeAgentProfile getProfile() {
        return profile;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName(profile.getDriverClass());
            connection = DriverManager.getConnection(profile.buildUrl(params), params.getUsername(), params.getPassword());
        });
    }

    @Override
    public boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName(profile.getDriverClass());
            try (Connection conn = DriverManager.getConnection(profile.buildUrl(params), params.getUsername(), params.getPassword())) {
                return conn.isValid(5);
            }
        });
    }

    @Override
    public List<DatabaseInfo> listDatabases() {
        return unchecked(() -> {
            List<DatabaseInfo> result = new ArrayList<>();
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement("SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new DatabaseInfo(rs.getString("datname")));
                }
            }
            return result;
        });
    }

    @Override
    public List<String> listSchemas() {
        return unchecked(() -> {
            List<String> result = new ArrayList<>();
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog','information_schema','pg_toast') ORDER BY schema_name"
            ); ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("schema_name"));
                }
            }
            return result;
        });
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        return unchecked(() -> {
            List<TableInfo> result = new ArrayList<>();
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(
                "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name"
            )) {
                stmt.setString(1, schema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableType = normalizeTableType(rs.getString("table_type"));
                        result.add(new TableInfo(rs.getString("table_name"), tableType, null));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<ObjectInfo> listObjects(String schema) {
        return unchecked(() -> {
            List<ObjectInfo> result = new ArrayList<>();
            for (TableInfo table : listTables(schema)) {
                result.add(new ObjectInfo(table.getName(), table.getTable_type(), schema, table.getComment()));
            }
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(
                "SELECT routine_name, routine_type FROM information_schema.routines WHERE routine_schema = ? ORDER BY routine_name"
            )) {
                stmt.setString(1, schema);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ObjectInfo(rs.getString(1), rs.getString(2), schema, null));
                    }
                }
            }
            return result;
        });
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
    public ObjectSource getObjectSource(String schema, String name, String objectType) {
        return unchecked(() -> {
            String upperType = objectType.toUpperCase();
            String sql;
            if ("VIEW".equals(upperType)) {
                sql = "SELECT pg_get_viewdef('\"" + schema + "\".\"" + name + "\"'::regclass, true)";
            } else if ("FUNCTION".equals(upperType)) {
                sql = "SELECT pg_get_functiondef(p.oid)\n" +
                    "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace\n" +
                    "WHERE n.nspname = ? AND p.proname = ? AND p.prokind = 'f'\n" +
                    "ORDER BY p.oid LIMIT 1";
            } else if ("PROCEDURE".equals(upperType)) {
                sql = "SELECT pg_get_functiondef(p.oid)\n" +
                    "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace\n" +
                    "WHERE n.nspname = ? AND p.proname = ? AND p.prokind = 'p'\n" +
                    "ORDER BY p.oid LIMIT 1";
            } else {
                throw new IllegalArgumentException("Unsupported object type: " + objectType);
            }

            String source;
            if ("VIEW".equals(upperType)) {
                try (java.sql.Statement stmt = requireConnection().createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                    source = rs.next() ? coalesce(rs.getString(1)) : "";
                }
            } else {
                try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                    stmt.setString(1, schema);
                    stmt.setString(2, name);
                    try (ResultSet rs = stmt.executeQuery()) {
                        source = rs.next() ? coalesce(rs.getString(1)) : "";
                    }
                }
            }
            return new ObjectSource(name, objectType, schema, source);
        });
    }

    @Override
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            Set<String> primaryKeys = primaryKeys(schema, table);
            List<ColumnInfo> result = new ArrayList<>();
            String sql = "SELECT column_name, data_type, is_nullable, column_default, " +
                "numeric_precision, numeric_scale, character_maximum_length " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position";
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                stmt.setString(1, schema);
                stmt.setString(2, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String colName = rs.getString("column_name");
                        result.add(new ColumnInfo(
                            colName,
                            rs.getString("data_type"),
                            "YES".equals(rs.getString("is_nullable")),
                            rs.getString("column_default"),
                            primaryKeys.contains(colName),
                            null,
                            null,
                            intObject(rs, "numeric_precision"),
                            intObject(rs, "numeric_scale"),
                            intObject(rs, "character_maximum_length")
                        ));
                    }
                }
            }
            return result;
        });
    }

    private Set<String> primaryKeys(String schema, String table) {
        return unchecked(() -> {
            Set<String> primaryKeys = new LinkedHashSet<>();
            String sql = "SELECT kcu.column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "ON tc.constraint_name = kcu.constraint_name " +
                "AND tc.table_schema = kcu.table_schema " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' " +
                "AND tc.table_schema = ? " +
                "AND tc.table_name = ?";
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                stmt.setString(1, schema);
                stmt.setString(2, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        primaryKeys.add(rs.getString("column_name"));
                    }
                }
            }
            return primaryKeys;
        });
    }

    @Override
    public List<IndexInfo> listIndexes(String schema, String table) {
        return unchecked(() -> {
            List<IndexInfo> result = new ArrayList<>();
            String sql = "SELECT i.relname AS index_name, am.amname AS index_type, " +
                "ix.indisunique AS is_unique, ix.indisprimary AS is_primary, " +
                "array_agg(a.attname ORDER BY k.n) AS columns " +
                "FROM pg_index ix " +
                "JOIN pg_class t ON t.oid = ix.indrelid " +
                "JOIN pg_class i ON i.oid = ix.indexrelid " +
                "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                "JOIN pg_am am ON am.oid = i.relam " +
                "CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, n) " +
                "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum " +
                "WHERE n.nspname = ? AND t.relname = ? " +
                "GROUP BY i.relname, am.amname, ix.indisunique, ix.indisprimary " +
                "ORDER BY i.relname";
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                stmt.setString(1, schema);
                stmt.setString(2, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Object[] columnArray = (Object[]) rs.getArray("columns").getArray();
                        List<String> columns = new ArrayList<>();
                        for (Object column : columnArray) {
                            columns.add(String.valueOf(column));
                        }
                        result.add(new IndexInfo(
                            rs.getString("index_name"),
                            columns,
                            rs.getBoolean("is_unique"),
                            rs.getBoolean("is_primary"),
                            null,
                            rs.getString("index_type"),
                            null,
                            null
                        ));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<ForeignKeyInfo> listForeignKeys(String schema, String table) {
        return unchecked(() -> {
            List<ForeignKeyInfo> result = new ArrayList<>();
            String sql = "SELECT tc.constraint_name, kcu.column_name, " +
                "ccu.table_name AS ref_table, ccu.column_name AS ref_column " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "ON tc.constraint_name = kcu.constraint_name " +
                "AND tc.table_schema = kcu.table_schema " +
                "JOIN information_schema.constraint_column_usage ccu " +
                "ON tc.constraint_name = ccu.constraint_name " +
                "AND tc.table_schema = ccu.table_schema " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                "AND tc.table_schema = ? " +
                "AND tc.table_name = ? " +
                "ORDER BY tc.constraint_name";
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                stmt.setString(1, schema);
                stmt.setString(2, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ForeignKeyInfo(
                            rs.getString("constraint_name"),
                            rs.getString("column_name"),
                            rs.getString("ref_table"),
                            rs.getString("ref_column")
                        ));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<TriggerInfo> listTriggers(String schema, String table) {
        return unchecked(() -> {
            List<TriggerInfo> result = new ArrayList<>();
            String sql = "SELECT trigger_name, event_manipulation, action_timing " +
                "FROM information_schema.triggers " +
                "WHERE trigger_schema = ? AND event_object_table = ? " +
                "ORDER BY trigger_name";
            try (java.sql.PreparedStatement stmt = requireConnection().prepareStatement(sql)) {
                stmt.setString(1, schema);
                stmt.setString(2, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new TriggerInfo(
                            rs.getString("trigger_name"),
                            rs.getString("event_manipulation"),
                            rs.getString("action_timing")
                        ));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public QueryResult executeQuery(String sql, String schema, ExecuteQueryOptions options) {
        return JdbcExecutor.INSTANCE.execute(
            requireConnection(),
            sql,
            schema,
            this::setSchemaSQL,
            options.getMaxRows(),
            options.getFetchSize(),
            this::getResultValue
        );
    }

    @Override
    public QueryPageResult executeQueryPage(String sql, String schema, QueryPageOptions options) {
        return JdbcExecutor.INSTANCE.executePage(
            requireConnection(),
            sql,
            schema,
            this::setSchemaSQL,
            options,
            this::getResultValue
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
            Connection conn = requireConnection();
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

    @Override
    public String setSchemaSQL(String schema) {
        return "SET search_path TO " + JdbcIdentifiers.INSTANCE.doubleQuote(schema);
    }

    @Override
    public void disconnect() {
        uncheckedVoid(() -> {
            if (connection != null) {
                connection.close();
            }
            connection = null;
        });
    }

    private Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("Not connected");
        }
        return connection;
    }

    private Object getResultValue(ResultSet rs, int index, int sqlType) {
        return unchecked(() -> {
            Object value;
            switch (sqlType) {
                case Types.BIGINT:
                    value = rs.getLong(index);
                    break;
                case Types.INTEGER:
                case Types.SMALLINT:
                case Types.TINYINT:
                    value = rs.getInt(index);
                    break;
                case Types.FLOAT:
                case Types.REAL:
                    value = rs.getFloat(index);
                    break;
                case Types.DOUBLE:
                    value = rs.getDouble(index);
                    break;
                case Types.DECIMAL:
                case Types.NUMERIC:
                    value = rs.getBigDecimal(index);
                    break;
                case Types.BOOLEAN:
                case Types.BIT:
                    value = rs.getBoolean(index);
                    break;
                default:
                    value = rs.getString(index);
                    break;
            }
            return rs.wasNull() ? null : value;
        });
    }

    private static String normalizeTableType(String type) {
        if (type == null || type.trim().isEmpty()) return "TABLE";
        if ("BASE TABLE".equals(type)) return "TABLE";
        return type;
    }

    private static String coalesce(String value) {
        return value == null ? "" : value;
    }

    private static String trimSql(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static Integer intObject(ResultSet rs, String column) throws Exception {
        Object value = rs.getObject(column);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static <T> T unchecked(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void uncheckedVoid(ThrowingRunnable runnable) {
        unchecked(() -> {
            runnable.run();
            return null;
        });
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
