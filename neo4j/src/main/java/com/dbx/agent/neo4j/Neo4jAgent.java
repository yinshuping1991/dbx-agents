package com.dbx.agent.neo4j;

import com.dbx.agent.BaseDatabaseAgent;
import com.dbx.agent.ColumnInfo;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseInfo;
import com.dbx.agent.ExecuteQueryOptions;
import com.dbx.agent.ForeignKeyInfo;
import com.dbx.agent.IndexInfo;
import com.dbx.agent.JdbcExecutor;
import com.dbx.agent.JsonRpcServer;
import com.dbx.agent.QueryResult;
import com.dbx.agent.TableInfo;
import com.dbx.agent.TriggerInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Neo4jAgent extends BaseDatabaseAgent {
    private Connection connection;

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public String setSchemaSQL(String schema) {
        return "";
    }

    @Override
    public void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName("org.neo4j.jdbc.Neo4jDriver");
            String url = "jdbc:neo4j://" + params.getHost() + ":" + params.getPort();
            connection = DriverManager.getConnection(url, params.getUsername(), params.getPassword());
        });
    }

    @Override
    public boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName("org.neo4j.jdbc.Neo4jDriver");
            String url = "jdbc:neo4j://" + params.getHost() + ":" + params.getPort();
            try (Connection conn = DriverManager.getConnection(url, params.getUsername(), params.getPassword())) {
                return conn.isValid(5);
            }
        });
    }

    @Override
    public List<DatabaseInfo> listDatabases() {
        return unchecked(() -> {
            Connection conn = requireConnected();
            List<DatabaseInfo> result = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    result.add(new DatabaseInfo(rs.getString("name")));
                }
            }
            result.sort(Comparator.comparing(DatabaseInfo::getName));
            return result;
        });
    }

    @Override
    public List<String> listSchemas() {
        return Collections.emptyList();
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        return unchecked(() -> {
            Connection conn = requireConnected();
            List<TableInfo> result = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("CALL db.labels()")) {
                while (rs.next()) {
                    result.add(new TableInfo(rs.getString(1), "TABLE", null));
                }
            }
            result.sort(Comparator.comparing(TableInfo::getName));
            return result;
        });
    }

    @Override
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            Connection conn = requireConnected();
            List<ColumnInfo> result = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("CALL db.schema.nodeTypeProperties()")) {
                while (rs.next()) {
                    String nodeLabels = rs.getString("nodeLabels");
                    if (nodeLabels == null) {
                        nodeLabels = "";
                    }
                    if (!nodeLabels.contains(table)) {
                        continue;
                    }

                    String propertyName = rs.getString("propertyName");
                    if (propertyName == null) {
                        continue;
                    }
                    String propertyTypes = rs.getString("propertyTypes");
                    if (propertyTypes == null) {
                        propertyTypes = "Unknown";
                    }
                    boolean isNullable;
                    try {
                        isNullable = !rs.getBoolean("mandatory");
                    } catch (Exception ignored) {
                        isNullable = true;
                    }

                    result.add(new ColumnInfo(
                        propertyName,
                        propertyTypes,
                        isNullable,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null
                    ));
                }
            }
            result.sort(Comparator.comparing(ColumnInfo::getName));
            return result;
        });
    }

    @Override
    public List<IndexInfo> listIndexes(String schema, String table) {
        return unchecked(() -> {
            Connection conn = requireConnected();
            List<IndexInfo> result = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW INDEXES")) {
                while (rs.next()) {
                    String labelsOrTypes = getStringOrDefault(rs, "labelsOrTypes", "");
                    if (!labelsOrTypes.contains(table)) {
                        continue;
                    }

                    String properties = getStringOrDefault(rs, "properties", "");
                    String uniqueness = getStringOrDefault(rs, "uniqueness", "");
                    result.add(new IndexInfo(
                        getStringOrDefault(rs, "name", ""),
                        parseProperties(properties),
                        "UNIQUE".equals(uniqueness.toUpperCase(Locale.ROOT)),
                        false,
                        null,
                        getStringOrNull(rs, "type"),
                        null,
                        null
                    ));
                }
            }
            result.sort(Comparator.comparing(IndexInfo::getName));
            return result;
        });
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
    public QueryResult executeTransaction(List<String> statements, String schema) {
        return unchecked(() -> {
            Connection conn = requireConnected();
            boolean savedAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            long start = System.currentTimeMillis();
            try {
                long totalAffected = 0;
                for (String sql : statements) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(trimSql(sql));
                        totalAffected += Math.max(stmt.getUpdateCount(), 0);
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

    public QueryResult executeQuery(String sql, String schema) {
        return executeQuery(sql, schema, new ExecuteQueryOptions());
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
            this::stringResultValue
        );
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

    private Object stringResultValue(ResultSet rs, int index, int sqlType) {
        return unchecked(() -> {
            Object value = rs.getObject(index);
            return rs.wasNull() ? null : value == null ? null : value.toString();
        });
    }

    private static List<String> parseProperties(String properties) {
        String trimmed = properties;
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        List<String> result = new ArrayList<>();
        for (String property : trimmed.split(",")) {
            String value = property.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String getStringOrDefault(ResultSet rs, String column, String defaultValue) {
        try {
            String value = rs.getString(column);
            return value == null ? defaultValue : value;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getStringOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimSql(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public static void main(String[] args) {
        new JsonRpcServer(new Neo4jAgent()).run();
    }
}
