package com.dbx.agent.bigquery;

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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BigQueryAgent extends BaseDatabaseAgent {
    private Connection connection;

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName("com.simba.googlebigquery.jdbc.Driver");
            connection = DriverManager.getConnection(buildUrl(params), params.getUsername(), params.getPassword());
        });
    }

    @Override
    public boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName("com.simba.googlebigquery.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(buildUrl(params), params.getUsername(), params.getPassword())) {
                return conn.isValid(5);
            }
        });
    }

    @Override
    public List<DatabaseInfo> listDatabases() {
        return unchecked(() -> {
            List<DatabaseInfo> result = new ArrayList<>();
            try (java.sql.Statement stmt = requireConnected().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT schema_name FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY schema_name")) {
                while (rs.next()) {
                    result.add(new DatabaseInfo(rs.getString(1)));
                }
            }
            return result;
        });
    }

    @Override
    public List<String> listSchemas() {
        List<String> result = new ArrayList<>();
        for (DatabaseInfo database : listDatabases()) {
            result.add(database.getName());
        }
        return result;
    }

    @Override
    public List<TableInfo> listTables(String schema) {
        return unchecked(() -> {
            List<TableInfo> result = new ArrayList<>();
            String quotedSchema = JdbcIdentifiers.INSTANCE.backtick(schema);
            String sql = "SELECT table_name, table_type FROM " + quotedSchema + ".INFORMATION_SCHEMA.TABLES ORDER BY table_name";
            try (java.sql.PreparedStatement stmt = requireConnected().prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableInfo(
                        rs.getString("table_name"),
                        normalizeTableType(rs.getString("table_type")),
                        null
                    ));
                }
            }
            return result;
        });
    }

    @Override
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            List<ColumnInfo> result = new ArrayList<>();
            String quotedSchema = JdbcIdentifiers.INSTANCE.backtick(schema);
            String sql = "SELECT column_name, data_type, is_nullable " +
                "FROM " + quotedSchema + ".INFORMATION_SCHEMA.COLUMNS " +
                "WHERE table_name = ? " +
                "ORDER BY ordinal_position";
            try (java.sql.PreparedStatement stmt = requireConnected().prepareStatement(sql)) {
                stmt.setString(1, table);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ColumnInfo(
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            "YES".equals(rs.getString("is_nullable")),
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
            }
            return result;
        });
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
        return JdbcExecutor.INSTANCE.execute(
            requireConnected(),
            sql,
            schema,
            this::setSchemaSQL,
            options.getMaxRows(),
            options.getFetchSize(),
            this::getResultValue
        );
    }

    @Override
    public String setSchemaSQL(String schema) {
        return "";
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

    private static String buildUrl(ConnectParams params) {
        return "jdbc:bigquery://" + params.getHost() + ":" + params.getPort() + ";ProjectId=" + params.getDatabase();
    }

    private static String normalizeTableType(String type) {
        return "BASE TABLE".equals(type) ? "TABLE" : type;
    }

    public static void main(String[] args) {
        new JsonRpcServer(new BigQueryAgent()).run();
    }
}
