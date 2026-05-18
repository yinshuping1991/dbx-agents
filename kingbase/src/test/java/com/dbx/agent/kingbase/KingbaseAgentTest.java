package com.dbx.agent.kingbase;

import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KingbaseAgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new KingbaseAgent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL sample_proc()";
    }

    @Test
    void declaresKingbasePostgresLikeProfile() {
        KingbaseAgent agent = new KingbaseAgent();

        Assertions.assertEquals("com.kingbase8.Driver", agent.getProfile().getDriverClass());
        Assertions.assertEquals("jdbc:kingbase8://{host}:{port}/{database}", agent.getProfile().getUrlTemplate());
    }
}
