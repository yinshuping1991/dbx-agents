package com.dbx.agent.tdengine;

import com.dbx.agent.BaseDatabaseAgent;
import com.dbx.agent.ColumnInfo;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseInfo;
import com.dbx.agent.ExecuteQueryOptions;
import com.dbx.agent.ForeignKeyInfo;
import com.dbx.agent.IndexInfo;
import com.dbx.agent.JdbcExecutor;
import com.dbx.agent.JsonRpcServer;
import com.dbx.agent.ObjectInfo;
import com.dbx.agent.ObjectSource;
import com.dbx.agent.QueryPageOptions;
import com.dbx.agent.QueryPageResult;
import com.dbx.agent.QueryResult;
import com.dbx.agent.TableInfo;
import com.dbx.agent.TriggerInfo;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TDengineAgent extends BaseDatabaseAgent {
    private static final DateTimeFormatter TDENGINE_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern NUMERIC_PRECISION_PATTERN =
        Pattern.compile("(?i)^(decimal|numeric)\\((\\d+)(?:,\\s*\\d+)?\\)");
    private static final Pattern NUMERIC_SCALE_PATTERN =
        Pattern.compile("(?i)^(decimal|numeric)\\(\\d+,\\s*(\\d+)\\)");
    private static final Pattern CHARACTER_LENGTH_PATTERN =
        Pattern.compile("(?i)^(binary|nchar|varchar|varbinary)\\((\\d+)\\)");

    private Connection connection;

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void connect(ConnectParams params) {
        uncheckedVoid(() -> {
            Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
            connection = DriverManager.getConnection(TDengineJdbcUrl.from(params), params.getUsername(), params.getPassword());
        });
    }

    @Override
    public boolean testConnection(ConnectParams params) {
        return unchecked(() -> {
            Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
            try (Connection conn = DriverManager.getConnection(TDengineJdbcUrl.from(params), params.getUsername(), params.getPassword())) {
                return conn.isValid(5);
            }
        });
    }

    @Override
    public List<DatabaseInfo> listDatabases() {
        return unchecked(() -> {
            List<DatabaseInfo> result = new ArrayList<>();
            try (java.sql.Statement stmt = requireConnected().createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
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
            result.addAll(queryTables("SHOW " + quoteQualifiedPrefix(schema) + "STABLES", "STABLE"));
            result.addAll(queryTables("SHOW " + quoteQualifiedPrefix(schema) + "TABLES", "TABLE"));

            Map<String, TableInfo> distinct = new LinkedHashMap<>();
            for (TableInfo table : result) {
                distinct.putIfAbsent(table.getTable_type() + ":" + table.getName(), table);
            }
            List<TableInfo> sorted = new ArrayList<>(distinct.values());
            sorted.sort(Comparator.comparing(table -> table.getName().toLowerCase(Locale.ROOT)));
            return sorted;
        });
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
    public List<ColumnInfo> getColumns(String schema, String table) {
        return unchecked(() -> {
            try (java.sql.Statement stmt = requireConnected().createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE " + qualifiedName(schema, table))) {
                return readDescribeColumns(rs);
            }
        });
    }

    @Override
    public ObjectSource getObjectSource(String schema, String name, String objectType) {
        String source = getCreateSql(schema, name, objectType);
        if (source.isBlank()) {
            source = getTableDdl(schema, name);
        }
        return new ObjectSource(name, objectType, schema, source);
    }

    @Override
    public String getTableDdl(String schema, String table) {
        String source = getCreateSql(schema, table, "STABLE");
        if (source.isBlank()) {
            source = getCreateSql(schema, table, "TABLE");
        }
        if (source.isBlank()) {
            return super.getTableDdl(schema, table);
        }
        return source;
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
            this::tdengineResultValue
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
            this::tdengineResultValue
        );
    }

    @Override
    public String setSchemaSQL(String schema) {
        return "USE " + quoteIdentifier(schema);
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

    private List<TableInfo> queryTables(String sql, String tableType) throws Exception {
        List<TableInfo> result = new ArrayList<>();
        try (java.sql.Statement stmt = requireConnected().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new TableInfo(rs.getString(1), tableType, null));
            }
        }
        return result;
    }

    private static List<ColumnInfo> readDescribeColumns(ResultSet rs) throws Exception {
        List<ColumnInfo> result = new ArrayList<>();
        int ordinal = 0;
        while (rs.next()) {
            ordinal += 1;
            String name = rs.getString(1);
            String dataType = coalesce(rs.getString(2));
            String note = optionalString(rs, 4);
            boolean isTag = note != null && note.toUpperCase(Locale.ROOT).contains("TAG");
            result.add(new ColumnInfo(
                name,
                dataType,
                ordinal != 1,
                null,
                ordinal == 1 && !isTag,
                note,
                isTag ? "TAG" : null,
                parseNumericPrecision(dataType),
                parseNumericScale(dataType),
                parseCharacterMaximumLength(dataType)
            ));
        }
        return result;
    }

    private String getCreateSql(String schema, String name, String objectType) {
        String showType = switch (objectType.toUpperCase(Locale.ROOT)) {
            case "STABLE", "SUPER TABLE", "SUPERTABLE" -> "STABLE";
            case "TABLE", "BASE TABLE", "CHILD TABLE" -> "TABLE";
            default -> null;
        };
        if (showType == null) {
            return "";
        }

        try (java.sql.Statement stmt = requireConnected().createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE " + showType + " " + qualifiedName(schema, name))) {
            if (!rs.next()) {
                return "";
            }
            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 2; i <= columnCount; i++) {
                String value = rs.getString(i);
                if (value != null && value.toUpperCase(Locale.ROOT).contains("CREATE")) {
                    return value;
                }
            }
            String value = rs.getString(columnCount);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private Object tdengineResultValue(ResultSet rs, int index, int sqlType) {
        return unchecked(() -> {
            Object value = switch (sqlType) {
                case Types.BIGINT -> rs.getLong(index);
                case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> rs.getInt(index);
                case Types.FLOAT, Types.REAL -> rs.getFloat(index);
                case Types.DOUBLE -> rs.getDouble(index);
                case Types.DECIMAL, Types.NUMERIC -> rs.getBigDecimal(index);
                case Types.BOOLEAN, Types.BIT -> rs.getBoolean(index);
                default -> rs.getObject(index);
            };
            return rs.wasNull() ? null : decodeTdengineValue(value);
        });
    }

    public static Object decodeTdengineValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof Timestamp timestamp) {
            return TDENGINE_TIMESTAMP_FORMAT.format(timestamp.toLocalDateTime());
        }
        return value;
    }

    private static String quoteQualifiedPrefix(String schema) {
        String trimmed = schema.trim();
        return trimmed.isEmpty() ? "" : quoteIdentifier(trimmed) + ".";
    }

    private static String qualifiedName(String schema, String name) {
        String table = quoteIdentifier(name);
        String trimmed = schema.trim();
        return trimmed.isEmpty() ? table : quoteIdentifier(trimmed) + "." + table;
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static Integer parseNumericPrecision(String dataType) {
        return parseIntGroup(NUMERIC_PRECISION_PATTERN, dataType, 2);
    }

    private static Integer parseNumericScale(String dataType) {
        return parseIntGroup(NUMERIC_SCALE_PATTERN, dataType, 2);
    }

    private static Integer parseCharacterMaximumLength(String dataType) {
        return parseIntGroup(CHARACTER_LENGTH_PATTERN, dataType, 2);
    }

    private static Integer parseIntGroup(Pattern pattern, String value, int group) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(group));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String optionalString(ResultSet rs, int index) {
        try {
            return rs.getString(index);
        } catch (Exception e) {
            return null;
        }
    }

    private static String coalesce(String value) {
        return value == null ? "" : value;
    }

    public static void main(String[] args) {
        new JsonRpcServer(new TDengineAgent()).run();
    }
}

final class TDengineJdbcUrl {
    private TDengineJdbcUrl() {
    }

    static String from(ConnectParams params) {
        String host = params.getHost().isBlank() ? "localhost" : params.getHost();
        int port = params.getPort() > 0 ? params.getPort() : 6041;
        String database = params.getDatabase().trim();
        String path = database.isBlank() ? "/" : "/" + database;
        String query = params.getUrl_params().trim();
        if (query.startsWith("?")) {
            query = query.substring(1);
        }
        String suffix = query.isBlank() ? "" : "?" + query;
        return "jdbc:TAOS-WS://" + host + ":" + port + path + suffix;
    }
}
