package com.dbx.agent.gaussdb;

import com.dbx.agent.BaseDatabaseAgent;
import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GaussdbAgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new GaussdbAgent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL sample_proc()";
    }

    @Test
    void agentExtendsBaseDatabaseAgent() {
        Assertions.assertTrue(BaseDatabaseAgent.class.isAssignableFrom(GaussdbAgent.class));
    }
}
