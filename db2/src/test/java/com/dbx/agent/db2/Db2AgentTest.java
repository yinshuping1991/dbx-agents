package com.dbx.agent.db2;

import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.ConnectParams;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Db2AgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new Db2Agent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL ADMIN_CMD('list applications')";
    }

    @Test
    void buildsJdbcUrlWithDb2PropertySuffix() {
        ConnectParams params = new ConnectParams();
        params.setHost("db.example.com");
        params.setPort(50000);
        params.setDatabase("SAMPLE");
        params.setUrl_params("sslConnection=true");

        assertEquals("jdbc:db2://db.example.com:50000/SAMPLE:sslConnection=true;", Db2Agent.buildUrl(params));
    }
}
