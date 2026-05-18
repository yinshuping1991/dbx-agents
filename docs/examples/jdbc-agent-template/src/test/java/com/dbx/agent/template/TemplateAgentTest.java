package com.dbx.agent.template;

import com.dbx.agent.ExecuteQueryOptions;
import com.dbx.agent.test.JdbcAgentFake;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TemplateAgentTest {
    @Test
    void executeQueryUsesStatementExecute() {
        TemplateAgent agent = new TemplateAgent();
        JdbcAgentFake.setPrivateConnection(agent, JdbcAgentFake.connection());

        agent.executeQuery("SHOW TABLES", null, new ExecuteQueryOptions());

        Assertions.assertEquals(Collections.singletonList("execute"), JdbcAgentFake.calls);
    }
}
