package com.dbx.agent.template;

import com.dbx.agent.BaseDatabaseAgent;
import com.dbx.agent.ColumnInfo;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseInfo;
import com.dbx.agent.ExecuteQueryOptions;
import com.dbx.agent.ForeignKeyInfo;
import com.dbx.agent.IndexInfo;
import com.dbx.agent.JdbcExecutor;
import com.dbx.agent.JdbcIdentifiers;
import com.dbx.agent.JsonRpcServer;
import com.dbx.agent.QueryResult;
import com.dbx.agent.TableInfo;
import com.dbx.agent.TriggerInfo;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TemplateAgent extends BaseDatabaseAgent {
    private static final String DRIVER_CLASS = "com.example.jdbc.TemplateDriver";
    private Connection connection;
    private String databaseName = "";

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName(DRIVER_CLASS);
            connection = DriverManager.getConnection(jdbcUrl(params), params.getUsername(), params.getPassword());
            databaseName = params.getDatabase();
        });
    }

    @Override
    public boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName(DRIVER_CLASS);
            try (Connection conn = DriverManager.getConnection(jdbcUrl(params), params.getUsername(), params.getPassword())) {
                return conn.isValid(5);
            }
        });
    }

    @Override
    public List<DatabaseInfo> listDatabases() {
        return Collections.singletonList(new DatabaseInfo(databaseName.isBlank() ? "default" : databaseName));
    }

    @Override
    public List<String> listSchemas() {
        return unchecked(() -> {
            List<String> result = new ArrayList<>();
            try (ResultSet rs = requireConnected().getMetaData().getSchemas()) {
                while (rs.next()) {
                    result.add(rs.getString("TABLE_SCHEM"));
                }
            }
            Collections.sort(result);
            return result;
        });
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        return unchecked(() -> {
            List<TableInfo> result = new ArrayList<>();
            try (ResultSet rs = requireConnected().getMetaData().getTables(null, schema, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    result.add(new TableInfo(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), null));
                }
            }
            result.sort(Comparator.comparing(TableInfo::getName));
            return result;
        });
    }

    @Override
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            DatabaseMetaData meta = requireConnected().getMetaData();
            Set<String> primaryKeys = new LinkedHashSet<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }

            List<ColumnInfo> result = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    Integer size = intOrNull(rs, "COLUMN_SIZE");
                    result.add(new ColumnInfo(
                        name,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        rs.getString("COLUMN_DEF"),
                        primaryKeys.contains(name),
                        null,
                        rs.getString("REMARKS"),
                        size,
                        intOrNull(rs, "DECIMAL_DIGITS"),
                        size
                    ));
                }
            }
            return result;
        });
    }

    @Override
    public List<IndexInfo> listIndexes(String schema, String table) {
        return unchecked(() -> {
            Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
            Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();

            try (ResultSet rs = requireConnected().getMetaData().getIndexInfo(null, schema, table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (name == null || column == null) {
                        continue;
                    }
                    columnsByIndex.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                    uniqueByIndex.put(name, !rs.getBoolean("NON_UNIQUE"));
                }
            }

            List<IndexInfo> result = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : columnsByIndex.entrySet()) {
                String name = entry.getKey();
                result.add(new IndexInfo(
                    name,
                    entry.getValue(),
                    uniqueByIndex.getOrDefault(name, false),
                    false,
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
            List<ForeignKeyInfo> result = new ArrayList<>();
            try (ResultSet rs = requireConnected().getMetaData().getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    result.add(new ForeignKeyInfo(
                        rs.getString("FK_NAME"),
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
    public QueryResult executeQuery(String sql, String schema, ExecuteQueryOptions options) {
        return JdbcExecutor.INSTANCE.execute(
            requireConnected(),
            sql,
            schema,
            this::setSchemaSQL,
            options.getMaxRows(),
            options.getFetchSize(),
            JdbcExecutor.INSTANCE::defaultResultValue
        );
    }

    @Override
    public String setSchemaSQL(String schema) {
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

    private static String jdbcUrl(ConnectParams params) {
        return "jdbc:template://" + params.getHost() + ":" + params.getPort() + "/" + params.getDatabase();
    }

    private static Integer intOrNull(ResultSet rs, String column) throws Exception {
        Object value = rs.getObject(column);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    public static void main(String[] args) {
        new JsonRpcServer(new TemplateAgent()).run();
    }
}
