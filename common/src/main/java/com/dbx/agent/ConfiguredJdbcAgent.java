package com.dbx.agent;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class ConfiguredJdbcAgent implements DatabaseAgent {
    private final JdbcAgentProfile profile;
    private Connection connection;
    private String configuredDatabase = "";

    protected ConfiguredJdbcAgent(JdbcAgentProfile profile) {
        this.profile = profile;
    }

    public JdbcAgentProfile getProfile() {
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
            configuredDatabase = params.getDatabase();
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
            Connection conn = requireConnection();
            Set<String> names = new LinkedHashSet<>();
            try {
                try (ResultSet rs = conn.getMetaData().getCatalogs()) {
                    while (rs.next()) {
                        addNonBlank(names, rs.getString("TABLE_CAT"));
                    }
                }
            } catch (Exception ignored) {
            }
            addNonBlank(names, configuredDatabase);
            addNonBlank(names, conn.getCatalog());

            List<DatabaseInfo> result = new ArrayList<>();
            for (String name : names) {
                result.add(new DatabaseInfo(name));
            }
            return result;
        });
    }

    @Override
    public List<String> listSchemas() {
        return unchecked(() -> {
            Connection conn = requireConnection();
            Set<String> names = new LinkedHashSet<>();
            DatabaseMetaData meta = conn.getMetaData();
            try {
                try (ResultSet rs = meta.getSchemas(null, null)) {
                    while (rs.next()) {
                        addNonBlank(names, rs.getString("TABLE_SCHEM"));
                    }
                }
            } catch (Exception first) {
                try (ResultSet rs = meta.getSchemas()) {
                    while (rs.next()) {
                        addNonBlank(names, rs.getString("TABLE_SCHEM"));
                    }
                }
            }
            addNonBlank(names, conn.getSchema());

            List<String> result = new ArrayList<>();
            for (String name : names) {
                if (!profile.getExcludedSchemas().contains(name.toUpperCase(Locale.ROOT))) {
                    result.add(name);
                }
            }
            Collections.sort(result);
            return result;
        });
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        return unchecked(() -> {
            DatabaseMetaData meta = requireConnection().getMetaData();
            List<TableInfo> result = new ArrayList<>();
            appendTables(result, meta, null, schema);
            if (result.isEmpty() && !configuredDatabase.trim().isEmpty()) {
                appendTables(result, meta, configuredDatabase, schema);
            }
            result.sort(Comparator.comparing(TableInfo::getName));
            return result;
        });
    }

    private void appendTables(List<TableInfo> result, DatabaseMetaData meta, String catalog, String schema) throws Exception {
        String[] tableTypes = profile.getTableTypes().toArray(new String[profile.getTableTypes().size()]);
        try (ResultSet rs = meta.getTables(catalog, blankToNull(schema), "%", tableTypes)) {
            while (rs.next()) {
                result.add(new TableInfo(
                    rs.getString("TABLE_NAME"),
                    normalizeTableType(rs.getString("TABLE_TYPE")),
                    rs.getString("REMARKS")
                ));
            }
        }
    }

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
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            DatabaseMetaData meta = requireConnection().getMetaData();
            Set<String> primaryKeys = primaryKeys(meta, null, schema, table);
            List<ColumnInfo> result = new ArrayList<>();
            appendColumns(result, meta, null, schema, table, primaryKeys);
            if (result.isEmpty() && !configuredDatabase.trim().isEmpty()) {
                Set<String> fallbackPrimaryKeys = primaryKeys(meta, configuredDatabase, schema, table);
                appendColumns(result, meta, configuredDatabase, schema, table, fallbackPrimaryKeys);
            }
            return result;
        });
    }

    private Set<String> primaryKeys(DatabaseMetaData meta, String catalog, String schema, String table) {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, blankToNull(schema), table)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null) {
                    keys.add(name);
                }
            }
        } catch (Exception ignored) {
        }
        return keys;
    }

    private void appendColumns(
        List<ColumnInfo> result,
        DatabaseMetaData meta,
        String catalog,
        String schema,
        String table,
        Set<String> primaryKeys
    ) throws Exception {
        try (ResultSet rs = meta.getColumns(catalog, blankToNull(schema), table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                result.add(new ColumnInfo(
                    name,
                    rs.getString("TYPE_NAME"),
                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    rs.getString("COLUMN_DEF"),
                    primaryKeys.contains(name),
                    null,
                    rs.getString("REMARKS"),
                    intOrNull(rs, "COLUMN_SIZE"),
                    intOrNull(rs, "DECIMAL_DIGITS"),
                    characterLength(rs)
                ));
            }
        }
    }

    @Override
    public List<IndexInfo> listIndexes(String schema, String table) {
        return unchecked(() -> {
            DatabaseMetaData meta = requireConnection().getMetaData();
            Map<String, List<IndexColumn>> indexes = new LinkedHashMap<>();
            Map<String, Boolean> unique = new LinkedHashMap<>();
            try (ResultSet rs = meta.getIndexInfo(null, blankToNull(schema), table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (name == null || column == null) {
                        continue;
                    }
                    indexes.computeIfAbsent(name, ignored -> new ArrayList<>()).add(
                        new IndexColumn(rs.getShort("ORDINAL_POSITION"), column)
                    );
                    unique.put(name, !rs.getBoolean("NON_UNIQUE"));
                }
            }

            List<IndexInfo> result = new ArrayList<>();
            for (Map.Entry<String, List<IndexColumn>> entry : indexes.entrySet()) {
                List<IndexColumn> orderedColumns = entry.getValue();
                orderedColumns.sort(Comparator.comparingInt(IndexColumn::getOrdinal));
                List<String> columns = new ArrayList<>();
                for (IndexColumn column : orderedColumns) {
                    columns.add(column.getName());
                }
                String name = entry.getKey();
                result.add(new IndexInfo(
                    name,
                    columns,
                    Boolean.TRUE.equals(unique.get(name)),
                    "PRIMARY".equalsIgnoreCase(name),
                    null,
                    null,
                    null,
                    null
                ));
            }
            return result;
        });
    }

    @Override
    public List<ForeignKeyInfo> listForeignKeys(String schema, String table) {
        return unchecked(() -> {
            DatabaseMetaData meta = requireConnection().getMetaData();
            List<ForeignKeyInfo> result = new ArrayList<>();
            try (ResultSet rs = meta.getImportedKeys(null, blankToNull(schema), table)) {
                while (rs.next()) {
                    String name = rs.getString("FK_NAME");
                    result.add(new ForeignKeyInfo(
                        name == null ? "" : name,
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")
                    ));
                }
            }
            return result;
        });
    }

    @Override
    public List<TriggerInfo> listTriggers(String schema, String table) {
        return Collections.emptyList();
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

        return DatabaseAgentKt.buildTableDdl(schema, table, getColumns(schema, table), indexes, foreignKeys);
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
            this::resultValue
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
            this::resultValue
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
        if (profile.getSkipExecutionContext()) {
            return "";
        }
        return "SET SCHEMA " + JdbcIdentifiers.INSTANCE.doubleQuote(schema);
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

    protected Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("Not connected");
        }
        return connection;
    }

    protected Object resultValue(ResultSet rs, int index, int sqlType) {
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
        if (type == null || type.trim().isEmpty()) {
            return "TABLE";
        }
        if ("BASE TABLE".equals(type.toUpperCase(Locale.ROOT))) {
            return "TABLE";
        }
        return type;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws Exception {
        Object value = rs.getObject(column);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Integer characterLength(ResultSet rs) throws Exception {
        String typeName = rs.getString("TYPE_NAME");
        if (typeName == null) {
            return null;
        }
        String normalized = typeName.toLowerCase(Locale.ROOT);
        if (!normalized.contains("char") && !normalized.contains("text")) {
            return null;
        }
        return intOrNull(rs, "COLUMN_SIZE");
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static void addNonBlank(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value);
        }
    }

    private static String trimSql(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
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

    private static final class IndexColumn {
        private final int ordinal;
        private final String name;

        private IndexColumn(int ordinal, String name) {
            this.ordinal = ordinal;
            this.name = name;
        }

        private int getOrdinal() {
            return ordinal;
        }

        private String getName() {
            return name;
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
