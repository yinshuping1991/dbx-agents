package com.dbx.agent.db2;

import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;

class Db2AgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new Db2Agent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL ADMIN_CMD('list applications')";
    }
}
