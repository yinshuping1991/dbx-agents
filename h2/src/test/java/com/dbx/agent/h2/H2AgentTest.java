package com.dbx.agent.h2;

import com.dbx.agent.BaseDatabaseAgent;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcExecutionBehaviorTest;
import com.dbx.agent.test.JdbcMetadataBehaviorTest;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class H2AgentMigrationTest {
    @Test
    void agentExtendsBaseDatabaseAgent() {
        Assertions.assertTrue(BaseDatabaseAgent.class.isAssignableFrom(H2Agent.class));
    }
}

class H2ExecutionBehaviorTest extends JdbcExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createConnectedAgent(String databaseName) {
        return H2AgentTestSupport.createH2Agent(databaseName);
    }

    @Override
    protected String resultSetSql() {
        return "CALL 42";
    }

    @Override
    protected List<String> expectedResultSetColumns() {
        return List.of("42");
    }

    @Override
    protected List<List<Object>> expectedResultSetRows() {
        return List.of(List.<Object>of(42));
    }

    @Override
    protected String rowsSql(int rowCount) {
        return "SELECT X FROM SYSTEM_RANGE(1, " + rowCount + ")";
    }
}

class H2MetadataBehaviorTest extends JdbcMetadataBehaviorTest {
    @Override
    protected DatabaseAgent createConnectedAgent(String databaseName) {
        return H2AgentTestSupport.createH2Agent(databaseName);
    }

    @Override
    protected List<String> metadataFixtureSql() {
        return List.of(
            "CREATE TABLE BETA_TABLE (ID INT PRIMARY KEY)",
            "CREATE TABLE ALPHA_TABLE (ID INT PRIMARY KEY)",
            "CREATE TABLE COLUMN_ORDER_SAMPLE (ID INT PRIMARY KEY, NAME VARCHAR(64), CREATED_AT TIMESTAMP)"
        );
    }

    @Override
    protected String metadataSchema() {
        return "PUBLIC";
    }

    @Override
    protected List<String> expectedTablesInOrder() {
        return List.of("ALPHA_TABLE", "BETA_TABLE", "COLUMN_ORDER_SAMPLE");
    }

    @Override
    protected String metadataColumnsTable() {
        return "COLUMN_ORDER_SAMPLE";
    }

    @Override
    protected List<String> expectedColumnsInOrder() {
        return List.of("ID", "NAME", "CREATED_AT");
    }
}

final class H2AgentTestSupport {
    private H2AgentTestSupport() {
    }

    static DatabaseAgent createH2Agent(String databaseName) {
        H2Agent agent = new H2Agent();
        agent.connect(new ConnectParams("", 0, "mem:" + databaseName + ";DB_CLOSE_DELAY=-1", "", "", "", ""));
        return agent;
    }
}
