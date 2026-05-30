package com.dbx.agent.xugu;

import com.dbx.agent.ConnectParams;
import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class XuguAgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new XuguAgent();
    }

    @Override
    protected String resultSetSql() {
        return "SELECT * FROM sample_table";
    }

    @Test
    void buildsOfficialJdbcUrl() {
        ConnectParams params = new ConnectParams();
        params.setHost("db.example.com");
        params.setPort(5138);
        params.setDatabase("demo");

        Assertions.assertEquals(
            "jdbc:xugu://db.example.com:5138/demo",
            XuguAgent.XUGU_PROFILE.buildUrl(params)
        );
    }
}
