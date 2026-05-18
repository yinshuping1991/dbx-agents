package com.dbx.agent.oracle;

import com.dbx.agent.DatabaseAgent;
import com.dbx.agent.test.JdbcFakeExecutionBehaviorTest;

class OracleAgentTest extends JdbcFakeExecutionBehaviorTest {
    @Override
    protected DatabaseAgent createAgent() {
        return new OracleAgent();
    }

    @Override
    protected String resultSetSql() {
        return "CALL DBMS_XPLAN.DISPLAY_CURSOR()";
    }
}
