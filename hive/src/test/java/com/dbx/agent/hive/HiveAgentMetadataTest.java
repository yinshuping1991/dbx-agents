package com.dbx.agent.hive;

import com.dbx.agent.test.JdbcAgentFake;
import com.dbx.agent.test.JdbcMetadataSqlFake;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HiveAgentMetadataTest {
    @Test
    void quotesSchemaAndTableIdentifiersInMetadataSql() {
        HiveAgent agent = new HiveAgent();
        Connection fake = JdbcMetadataSqlFake.connection();
        JdbcAgentFake.setPrivateConnection(agent, fake);

        agent.listTables("bad`schema");
        agent.getColumns("bad`schema", "bad`table");

        Assertions.assertEquals(
            List.of(
                "USE `bad``schema`",
                "SHOW TABLES",
                "USE `bad``schema`",
                "DESCRIBE `bad``table`"
            ),
            JdbcMetadataSqlFake.statements
        );
    }
}
