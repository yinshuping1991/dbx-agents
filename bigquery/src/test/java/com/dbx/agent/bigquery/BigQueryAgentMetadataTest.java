package com.dbx.agent.bigquery;

import com.dbx.agent.test.JdbcAgentFake;
import com.dbx.agent.test.JdbcMetadataSqlFake;
import java.sql.Connection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BigQueryAgentMetadataTest {
    @Test
    void quotesSchemaIdentifiersInMetadataSql() {
        BigQueryAgent agent = new BigQueryAgent();
        Connection fake = JdbcMetadataSqlFake.connection();
        JdbcAgentFake.setPrivateConnection(agent, fake);

        agent.listTables("bad`schema");
        agent.getColumns("bad`schema", "sample");

        Assertions.assertTrue(
            JdbcMetadataSqlFake.statements.stream()
                .anyMatch(statement -> statement.contains("FROM `bad``schema`.INFORMATION_SCHEMA.TABLES"))
        );
        Assertions.assertTrue(
            JdbcMetadataSqlFake.statements.stream()
                .anyMatch(statement -> statement.contains("FROM `bad``schema`.INFORMATION_SCHEMA.COLUMNS"))
        );
    }
}
