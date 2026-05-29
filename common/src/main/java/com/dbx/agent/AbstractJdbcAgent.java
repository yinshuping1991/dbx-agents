package com.dbx.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

public abstract class AbstractJdbcAgent extends BaseDatabaseAgent {
    private Connection connection;
    private String configuredDatabase = "";

    @Override
    public final Connection getConnection() {
        return connection;
    }

    @Override
    public final void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName(driverClass());
            connection = openConnection(params);
            configuredDatabase = params.getDatabase();
            afterConnect(params, connection);
        });
    }

    @Override
    public final boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName(driverClass());
            try (Connection conn = openConnection(params)) {
                return conn.isValid(5);
            }
        });
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
            this::resultValue
        );
    }

    @Override
    public QueryPageResult executeQueryPage(String sql, String schema, QueryPageOptions options) {
        return JdbcExecutor.INSTANCE.executePage(
            requireConnected(),
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
        return TransactionExecutor.executeUpdateStatements(requireConnected(), statements, schema, this::setSchemaSQL);
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

    protected abstract String driverClass();

    protected abstract String buildJdbcUrl(ConnectParams params);

    protected Connection openConnection(ConnectParams params) throws Exception {
        return DriverManager.getConnection(buildJdbcUrl(params), params.getUsername(), params.getPassword());
    }

    protected void afterConnect(ConnectParams params, Connection connection) throws Exception {
    }

    protected String getConfiguredDatabase() {
        return configuredDatabase;
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
}
