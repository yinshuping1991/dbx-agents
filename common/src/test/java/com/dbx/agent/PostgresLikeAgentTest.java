package com.dbx.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresLikeAgentTest {
    @Test
    void metadataQueriesUsePgCatalogInsteadOfInformationSchema() {
        TestPostgresLikeAgent agent = new TestPostgresLikeAgent();
        agent.connect(new ConnectParams());

        agent.listSchemas();
        agent.listTables("app");
        agent.listObjects("app");
        agent.getColumns("app", "orders");
        agent.listForeignKeys("app", "orders");
        agent.listTriggers("app", "orders");

        String sql = String.join("\n", MetadataSqlFake.statements);

        assertFalse(sql.contains("FROM information_schema"), sql);
        assertFalse(sql.contains("JOIN information_schema"), sql);
        assertTrue(sql.contains("pg_catalog.pg_namespace"), sql);
        assertTrue(sql.contains("pg_catalog.pg_class"), sql);
        assertTrue(sql.contains("pg_catalog.pg_proc"), sql);
        assertTrue(sql.contains("pg_catalog.pg_attribute"), sql);
        assertTrue(sql.contains("pg_catalog.pg_constraint"), sql);
        assertTrue(sql.contains("pg_catalog.pg_trigger"), sql);
    }

    private static final class TestPostgresLikeAgent extends PostgresLikeAgent {
        private TestPostgresLikeAgent() {
            super(new PostgresLikeAgentProfile(
                PostgresLikeAgentTest.class.getName(),
                "jdbc:test://{host}:{port}/{database}"
            ));
        }

        @Override
        protected Connection openConnection(ConnectParams params) {
            return MetadataSqlFake.connection();
        }
    }

    private static final class MetadataSqlFake {
        private static final List<String> statements = new ArrayList<String>();

        private static Connection connection() {
            statements.clear();
            return proxy(Connection.class, new MethodHandler() {
                @Override
                public Object handle(Method method, Object[] args) {
                    String name = method.getName();
                    if ("prepareStatement".equals(name)) {
                        statements.add((String) args[0]);
                        return preparedStatement();
                    }
                    if ("isClosed".equals(name)) {
                        return false;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private static PreparedStatement preparedStatement() {
            final ResultSet resultSet = emptyResultSet();
            return proxy(PreparedStatement.class, new MethodHandler() {
                @Override
                public Object handle(Method method, Object[] args) {
                    String name = method.getName();
                    if ("executeQuery".equals(name)) {
                        return resultSet;
                    }
                    if ("setString".equals(name)) {
                        statements.add("param:" + args[0] + "=" + args[1]);
                        return null;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
        }

        private static ResultSet emptyResultSet() {
            final ResultSetMetaData metadata = proxy(ResultSetMetaData.class, new MethodHandler() {
                @Override
                public Object handle(Method method, Object[] args) {
                    if ("getColumnCount".equals(method.getName())) {
                        return 0;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
            return proxy(ResultSet.class, new MethodHandler() {
                @Override
                public Object handle(Method method, Object[] args) {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return false;
                    }
                    if ("getMetaData".equals(name)) {
                        return metadata;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
            });
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
    }

    private interface MethodHandler {
        Object handle(Method method, Object[] args);
    }
}
