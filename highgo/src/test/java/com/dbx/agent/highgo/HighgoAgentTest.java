package com.dbx.agent.highgo;

import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HighgoAgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new HighgoAgent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL sample_proc()";
    }

    @Test
    void declaresHighgoPostgresLikeProfile() {
        HighgoAgent agent = new HighgoAgent();

        Assertions.assertEquals("com.highgo.jdbc.Driver", agent.getProfile().getDriverClass());
        Assertions.assertEquals("jdbc:highgo://{host}:{port}/{database}", agent.getProfile().getUrlTemplate());
    }
}
