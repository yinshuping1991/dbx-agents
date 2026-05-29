package com.dbx.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardJdbcMetadataTest {
    private final JdbcAgentProfile profile = new JdbcAgentProfile(
        "example.Driver",
        "jdbc:example://{host}:{port}/{database}",
        0,
        false,
        Collections.singleton("SYS"),
        Arrays.asList("TABLE", "VIEW", "BASE TABLE")
    );

    @Test
    void listsSchemasWithFilteringAndStableOrdering() {
        Connection conn = connection(
            rows(row("TABLE_SCHEM", "APP"), row("TABLE_SCHEM", "SYS"), row("TABLE_SCHEM", "PUBLIC")),
            rows(),
            rows(),
            rows(),
            rows(),
            rows()
        );

        assertEquals(Arrays.asList("APP", "PUBLIC"), StandardJdbcMetadata.INSTANCE.listSchemas(conn, profile));
    }

    @Test
    void listsTablesWithNormalizedTypesAndStableOrdering() {
        Connection conn = connection(
            rows(),
            rows(row("TABLE_NAME", "ZETA", "TABLE_TYPE", "BASE TABLE", "REMARKS", "last"),
                row("TABLE_NAME", "ALPHA", "TABLE_TYPE", "VIEW", "REMARKS", "first")),
            rows(),
            rows(),
            rows(),
            rows()
        );

        List<TableInfo> tables = StandardJdbcMetadata.INSTANCE.listTables(conn, profile, "", "APP");

        assertEquals("ALPHA", tables.get(0).getName());
        assertEquals("VIEW", tables.get(0).getTable_type());
        assertEquals("ZETA", tables.get(1).getName());
        assertEquals("TABLE", tables.get(1).getTable_type());
    }

    @Test
    void mapsColumnsWithPrimaryKeysAndLengths() {
        Connection conn = connection(
            rows(),
            rows(),
            rows(row("COLUMN_NAME", "ID")),
            rows(row(
                "COLUMN_NAME", "ID",
                "TYPE_NAME", "INTEGER",
                "NULLABLE", DatabaseMetaData.columnNoNulls,
                "COLUMN_DEF", null,
                "REMARKS", "identifier",
                "COLUMN_SIZE", 10,
                "DECIMAL_DIGITS", 0
            ), row(
                "COLUMN_NAME", "NAME",
                "TYPE_NAME", "VARCHAR",
                "NULLABLE", DatabaseMetaData.columnNullable,
                "COLUMN_DEF", null,
                "REMARKS", null,
                "COLUMN_SIZE", 64,
                "DECIMAL_DIGITS", 0
            )),
            rows(),
            rows()
        );

        List<ColumnInfo> columns = StandardJdbcMetadata.INSTANCE.getColumns(conn, profile, "", "APP", "ORDERS");

        assertEquals("ID", columns.get(0).getName());
        assertTrue(columns.get(0).getIs_primary_key());
        assertFalse(columns.get(0).getIs_nullable());
        assertEquals("NAME", columns.get(1).getName());
        assertEquals(Integer.valueOf(64), columns.get(1).getCharacter_maximum_length());
    }

    @Test
    void groupsIndexColumnsInOrdinalOrder() {
        Connection conn = connection(
            rows(),
            rows(),
            rows(),
            rows(),
            rows(row("INDEX_NAME", "IDX_ORDERS", "COLUMN_NAME", "B", "ORDINAL_POSITION", (short) 2, "NON_UNIQUE", true),
                row("INDEX_NAME", "IDX_ORDERS", "COLUMN_NAME", "A", "ORDINAL_POSITION", (short) 1, "NON_UNIQUE", true)),
            rows()
        );

        List<IndexInfo> indexes = StandardJdbcMetadata.INSTANCE.listIndexes(conn, "APP", "ORDERS");

        assertEquals(1, indexes.size());
        assertEquals(Arrays.asList("A", "B"), indexes.get(0).getColumns());
        assertFalse(indexes.get(0).getIs_unique());
    }

    @Test
    void mapsForeignKeysAndEmptyTriggers() {
        Connection conn = connection(
            rows(),
            rows(),
            rows(),
            rows(),
            rows(),
            rows(row("FK_NAME", null, "FKCOLUMN_NAME", "CUSTOMER_ID", "PKTABLE_NAME", "CUSTOMERS", "PKCOLUMN_NAME", "ID"))
        );

        List<ForeignKeyInfo> foreignKeys = StandardJdbcMetadata.INSTANCE.listForeignKeys(conn, "APP", "ORDERS");

        assertEquals("", foreignKeys.get(0).getName());
        assertEquals("CUSTOMER_ID", foreignKeys.get(0).getColumn());
        assertEquals(Collections.emptyList(), StandardJdbcMetadata.INSTANCE.listTriggers("APP", "ORDERS"));
    }

    private static Connection connection(
        ResultSet schemas,
        ResultSet tables,
        ResultSet primaryKeys,
        ResultSet columns,
        ResultSet indexes,
        ResultSet foreignKeys
    ) {
        DatabaseMetaData meta = proxy(DatabaseMetaData.class, new MethodHandler() {
            @Override
            public Object handle(Method method, Object[] args) {
                String name = method.getName();
                if ("getSchemas".equals(name)) {
                    return schemas;
                }
                if ("getTables".equals(name)) {
                    return tables;
                }
                if ("getPrimaryKeys".equals(name)) {
                    return primaryKeys;
                }
                if ("getColumns".equals(name)) {
                    return columns;
                }
                if ("getIndexInfo".equals(name)) {
                    return indexes;
                }
                if ("getImportedKeys".equals(name)) {
                    return foreignKeys;
                }
                if ("getCatalogs".equals(name)) {
                    return rows();
                }
                return defaultValue(method.getReturnType());
            }
        });
        return proxy(Connection.class, new MethodHandler() {
            @Override
            public Object handle(Method method, Object[] args) {
                if ("getMetaData".equals(method.getName())) {
                    return meta;
                }
                if ("getSchema".equals(method.getName())) {
                    return null;
                }
                if ("getCatalog".equals(method.getName())) {
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static ResultSet rows(Map<String, Object>... rows) {
        return proxy(ResultSet.class, new MethodHandler() {
            private int index = -1;

            @Override
            public Object handle(Method method, Object[] args) {
                String name = method.getName();
                if ("next".equals(name)) {
                    index += 1;
                    return index < rows.length;
                }
                if ("close".equals(name)) {
                    return null;
                }
                Object value = rows[index].get(args[0]);
                if ("getString".equals(name)) {
                    return value == null ? null : String.valueOf(value);
                }
                if ("getObject".equals(name)) {
                    return value;
                }
                if ("getInt".equals(name)) {
                    return value instanceof Number ? ((Number) value).intValue() : 0;
                }
                if ("getShort".equals(name)) {
                    return value instanceof Number ? ((Number) value).shortValue() : 0;
                }
                if ("getBoolean".equals(name)) {
                    return value instanceof Boolean && (Boolean) value;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static <T> T proxy(Class<T> type, final MethodHandler handler) {
        InvocationHandler invocationHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return handler.handle(method, args);
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler));
    }

    private static Object defaultValue(Class<?> type) {
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Byte.TYPE.equals(type)) {
            return (byte) 0;
        }
        if (Short.TYPE.equals(type)) {
            return (short) 0;
        }
        if (Integer.TYPE.equals(type)) {
            return 0;
        }
        if (Long.TYPE.equals(type)) {
            return 0L;
        }
        if (Float.TYPE.equals(type)) {
            return 0f;
        }
        if (Double.TYPE.equals(type)) {
            return 0.0d;
        }
        if (Character.TYPE.equals(type)) {
            return '\0';
        }
        return null;
    }

    private interface MethodHandler {
        Object handle(Method method, Object[] args);
    }
}
