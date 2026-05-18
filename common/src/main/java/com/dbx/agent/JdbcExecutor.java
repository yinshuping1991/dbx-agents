package com.dbx.agent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class JdbcExecutor {
    public static final JdbcExecutor INSTANCE = new JdbcExecutor();
    public static final int DEFAULT_MAX_ROWS = 10000;
    public static final long QUERY_SESSION_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, QuerySession> sessions = new ConcurrentHashMap<>();

    private JdbcExecutor() {
    }

    public QueryResult execute(Connection conn, String sql, String schema, Function<String, String> setSchemaSql) {
        return execute(conn, sql, schema, setSchemaSql, DEFAULT_MAX_ROWS, null, this::defaultResultValue);
    }

    public QueryResult execute(
        Connection conn,
        String sql,
        String schema,
        Function<String, String> setSchemaSql,
        int maxRows,
        Integer fetchSize
    ) {
        return execute(conn, sql, schema, setSchemaSql, maxRows, fetchSize, this::defaultResultValue);
    }

    public QueryResult execute(
        Connection conn,
        String sql,
        String schema,
        Function<String, String> setSchemaSql,
        int maxRows,
        Integer fetchSize,
        ResultValueReader valueReader
    ) {
        return unchecked(() -> {
            String trimmedSql = trimSql(sql);
            String upperSql = trimmedSql.toUpperCase(Locale.ROOT).trim();
            long start = System.currentTimeMillis();

            if ("BEGIN".equals(upperSql) || "BEGIN TRANSACTION".equals(upperSql)) {
                conn.setAutoCommit(false);
                return emptyQueryResult(start);
            }
            if ("COMMIT".equals(upperSql)) {
                conn.commit();
                conn.setAutoCommit(true);
                return emptyQueryResult(start);
            }
            if ("ROLLBACK".equals(upperSql)) {
                conn.rollback();
                conn.setAutoCommit(true);
                return emptyQueryResult(start);
            }

            applySchema(conn, schema, setSchemaSql);

            try (Statement stmt = conn.createStatement()) {
                int effectiveMaxRows = Math.max(maxRows, 1);
                stmt.setMaxRows(effectiveMaxRows + 1);
                if (fetchSize != null && fetchSize > 0) {
                    stmt.setFetchSize(fetchSize);
                }
                boolean hasResultSet = stmt.execute(trimmedSql);
                long elapsed = System.currentTimeMillis() - start;
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        return readResultSet(rs, elapsed, effectiveMaxRows, valueReader);
                    }
                }

                int updateCount = stmt.getUpdateCount();
                return new QueryResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    updateCount >= 0 ? updateCount : 0,
                    elapsed,
                    false
                );
            }
        });
    }

    public QueryResult readResultSet(ResultSet rs, long executionTimeMs) {
        return readResultSet(rs, executionTimeMs, DEFAULT_MAX_ROWS, this::defaultResultValue);
    }

    public QueryResult readResultSet(
        ResultSet rs,
        long executionTimeMs,
        int maxRows,
        ResultValueReader valueReader
    ) {
        return unchecked(() -> {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }

            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                rows.add(rowValues(rs, valueReader));
            }

            return new QueryResult(columns, rows, 0L, executionTimeMs, truncated);
        });
    }

    public QueryPageResult executePage(
        Connection conn,
        String sql,
        String schema,
        Function<String, String> setSchemaSql
    ) {
        return executePage(conn, sql, schema, setSchemaSql, new QueryPageOptions(), this::defaultResultValue);
    }

    public QueryPageResult executePage(
        Connection conn,
        String sql,
        String schema,
        Function<String, String> setSchemaSql,
        QueryPageOptions options
    ) {
        return executePage(conn, sql, schema, setSchemaSql, options, this::defaultResultValue);
    }

    public QueryPageResult executePage(
        Connection conn,
        String sql,
        String schema,
        Function<String, String> setSchemaSql,
        QueryPageOptions options,
        ResultValueReader valueReader
    ) {
        return unchecked(() -> {
            expireIdleQuerySessions();
            String trimmedSql = trimSql(sql);
            String upperSql = trimmedSql.toUpperCase(Locale.ROOT).trim();
            long start = System.currentTimeMillis();

            if ("BEGIN".equals(upperSql) || "BEGIN TRANSACTION".equals(upperSql)) {
                conn.setAutoCommit(false);
                return emptyQueryPageResult(start);
            }
            if ("COMMIT".equals(upperSql)) {
                conn.commit();
                conn.setAutoCommit(true);
                return emptyQueryPageResult(start);
            }
            if ("ROLLBACK".equals(upperSql)) {
                conn.rollback();
                conn.setAutoCommit(true);
                return emptyQueryPageResult(start);
            }

            applySchema(conn, schema, setSchemaSql);

            Statement stmt = conn.createStatement();
            try {
                if (options.getFetchSize() != null && options.getFetchSize() > 0) {
                    stmt.setFetchSize(options.getFetchSize());
                }
                boolean hasResultSet = stmt.execute(trimmedSql);
                long elapsed = System.currentTimeMillis() - start;
                if (!hasResultSet) {
                    int updateCount = stmt.getUpdateCount();
                    stmt.close();
                    return new QueryPageResult(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        updateCount >= 0 ? updateCount : 0,
                        elapsed
                    );
                }

                ResultSet rs = stmt.getResultSet();
                ResultSetMetaData meta = rs.getMetaData();
                String sessionId = UUID.randomUUID().toString();
                QuerySession session = new QuerySession(
                    sessionId,
                    stmt,
                    rs,
                    resultColumns(meta),
                    Math.max(options.getMaxRows(), 1),
                    valueReader
                );
                sessions.put(sessionId, session);
                return readSessionPage(session, options.getPageSize(), elapsed);
            } catch (Exception e) {
                try {
                    stmt.close();
                } catch (Exception ignored) {
                }
                throw e;
            }
        });
    }

    public QueryPageResult fetchPage(String sessionId, int pageSize) {
        expireIdleQuerySessions();
        QuerySession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Query session not found");
        }
        synchronized (session) {
            return readSessionPage(session, pageSize, 0L);
        }
    }

    public boolean closeQuerySession(String sessionId) {
        return closeSession(sessionId);
    }

    public void closeAllQuerySessions() {
        List<String> ids = new ArrayList<>(sessions.keySet());
        for (String id : ids) {
            closeSession(id);
        }
    }

    public int expireIdleQuerySessions() {
        return expireIdleQuerySessions(System.currentTimeMillis(), QUERY_SESSION_IDLE_TIMEOUT_MILLIS);
    }

    public int expireIdleQuerySessions(long nowMillis, long idleTimeoutMillis) {
        if (idleTimeoutMillis < 0) {
            return 0;
        }
        int closed = 0;
        List<QuerySession> snapshot = new ArrayList<>(sessions.values());
        for (QuerySession session : snapshot) {
            boolean expired;
            synchronized (session) {
                expired = nowMillis - session.lastAccessedAtMillis >= idleTimeoutMillis;
            }
            if (expired && closeSession(session.id)) {
                closed += 1;
            }
        }
        return closed;
    }

    public Object defaultResultValue(ResultSet rs, int index, int sqlType) throws SQLException {
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
                value = rs.getObject(index);
                break;
        }
        return rs.wasNull() ? null : value;
    }

    private QueryPageResult readSessionPage(QuerySession session, int pageSize, long executionTimeMs) {
        return unchecked(() -> {
            session.lastAccessedAtMillis = System.currentTimeMillis();
            int effectivePageSize = Math.max(pageSize, 1);
            List<List<Object>> rows = new ArrayList<>();

            if (session.pendingRow != null) {
                rows.add(session.pendingRow);
                session.pendingRow = null;
            }

            while (rows.size() < effectivePageSize && session.rowsRead < session.maxRows) {
                if (!session.resultSet.next()) {
                    closeSession(session.id);
                    return new QueryPageResult(session.columns, rows, 0L, executionTimeMs, false, null, false);
                }
                rows.add(rowValues(session.resultSet, session.valueReader));
                session.rowsRead += 1;
            }

            if (session.rowsRead >= session.maxRows) {
                boolean truncated = session.resultSet.next();
                closeSession(session.id);
                return new QueryPageResult(session.columns, rows, 0L, executionTimeMs, truncated, null, false);
            }

            boolean hasMore = session.resultSet.next();
            if (!hasMore) {
                closeSession(session.id);
                return new QueryPageResult(session.columns, rows, 0L, executionTimeMs, false, null, false);
            }

            session.pendingRow = rowValues(session.resultSet, session.valueReader);
            session.rowsRead += 1;
            return new QueryPageResult(session.columns, rows, 0L, executionTimeMs, false, session.id, true);
        });
    }

    private boolean closeSession(String sessionId) {
        QuerySession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            try {
                session.resultSet.close();
            } catch (Exception ignored) {
            }
            try {
                session.statement.close();
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    private List<String> resultColumns(ResultSetMetaData meta) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }
        return columns;
    }

    private List<Object> rowValues(ResultSet rs, ResultValueReader valueReader) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Object> row = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.add(valueReader.read(rs, i, meta.getColumnType(i)));
        }
        return row;
    }

    private void applySchema(Connection conn, String schema, Function<String, String> setSchemaSql) throws SQLException {
        if (schema == null || schema.trim().isEmpty()) {
            return;
        }
        String schemaSql = setSchemaSql.apply(schema);
        if (schemaSql == null || schemaSql.trim().isEmpty()) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(schemaSql);
        }
    }

    private QueryResult emptyQueryResult(long start) {
        return new QueryResult(
            Collections.emptyList(),
            Collections.emptyList(),
            0L,
            System.currentTimeMillis() - start
        );
    }

    private QueryPageResult emptyQueryPageResult(long start) {
        return new QueryPageResult(
            Collections.emptyList(),
            Collections.emptyList(),
            0L,
            System.currentTimeMillis() - start
        );
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

    @FunctionalInterface
    public interface ResultValueReader {
        Object read(ResultSet rs, int index, int sqlType) throws SQLException;
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class QuerySession {
        private final String id;
        private final Statement statement;
        private final ResultSet resultSet;
        private final List<String> columns;
        private final int maxRows;
        private final ResultValueReader valueReader;
        private int rowsRead;
        private List<Object> pendingRow;
        private long lastAccessedAtMillis;

        private QuerySession(
            String id,
            Statement statement,
            ResultSet resultSet,
            List<String> columns,
            int maxRows,
            ResultValueReader valueReader
        ) {
            this.id = id;
            this.statement = statement;
            this.resultSet = resultSet;
            this.columns = columns;
            this.maxRows = maxRows;
            this.valueReader = valueReader;
            this.lastAccessedAtMillis = System.currentTimeMillis();
        }
    }
}
