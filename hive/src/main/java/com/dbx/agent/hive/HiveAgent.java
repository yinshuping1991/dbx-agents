package com.dbx.agent.hive;

import com.dbx.agent.AbstractJdbcAgent;
import com.dbx.agent.ColumnInfo;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseInfo;
import com.dbx.agent.ForeignKeyInfo;
import com.dbx.agent.IndexInfo;
import com.dbx.agent.JdbcIdentifiers;
import com.dbx.agent.JsonRpcServer;
import com.dbx.agent.TableInfo;
import com.dbx.agent.TriggerInfo;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HiveAgent extends AbstractJdbcAgent {

    @Override
    protected String driverClass() {
        return "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    protected String buildJdbcUrl(ConnectParams params) {
        return buildUrl(params);
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
            result.sort(Comparator.comparing(DatabaseInfo::getName));
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
            useSchema(schema);
            try (java.sql.Statement stmt = requireConnected().createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
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
            List<ColumnInfo> result = new ArrayList<>();
            useSchema(schema);
            try (java.sql.Statement stmt = requireConnected().createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE " + JdbcIdentifiers.INSTANCE.backtick(table))) {
                while (rs.next()) {
                    String colName = trimToNull(rs.getString(1));
                    if (colName == null || colName.startsWith("#")) {
                        continue;
                    }
                    result.add(new ColumnInfo(
                        colName,
                        trimToEmpty(rs.getString(2)),
                        true,
                        null,
                        false,
                        null,
                        optionalComment(rs),
                        null,
                        null,
                        null
                    ));
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
    public String setSchemaSQL(String schema) {
        return "USE " + JdbcIdentifiers.INSTANCE.backtick(schema);
    }

    @Override
    protected Object resultValue(ResultSet rs, int index, int sqlType) {
        return unchecked(() -> {
            Object value = rs.getObject(index);
            return rs.wasNull() ? null : value == null ? null : value.toString();
        });
    }

    private void useSchema(String schema) throws Exception {
        try (java.sql.Statement stmt = requireConnected().createStatement()) {
            stmt.execute(setSchemaSQL(schema));
        }
    }

    private static String buildUrl(ConnectParams params) {
        return "jdbc:hive2://" + params.getHost() + ":" + params.getPort() + "/" + params.getDatabase();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String optionalComment(ResultSet rs) {
        return unchecked(() -> {
            try {
                String comment = trimToNull(rs.getString(3));
                return comment;
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static void main(String[] args) {
        new JsonRpcServer(new HiveAgent()).run();
    }
}
