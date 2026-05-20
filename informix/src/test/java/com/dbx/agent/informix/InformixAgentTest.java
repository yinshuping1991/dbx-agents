package com.dbx.agent.informix;

import com.dbx.agent.ConnectParams;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InformixAgentTest {
    @Test
    void buildsJdbcUrlWithExplicitInformixServerAndLocaleParameters() {
        String url = InformixAgent.buildJdbcUrl(
            new ConnectParams(
                "172.26.128.159",
                20013,
                "testdb",
                "",
                "",
                "INFORMIXSERVER=informix;CLIENT_LOCALE=en_US.utf8;DB_LOCALE=en_US.utf8",
                ""
            )
        );

        Assertions.assertEquals(
            "jdbc:informix-sqli://172.26.128.159:20013/testdb:INFORMIXSERVER=informix;CLIENT_LOCALE=en_US.utf8;DB_LOCALE=en_US.utf8",
            url
        );
    }

    @Test
    void fallsBackToHostAsInformixServerWhenNoExplicitServerIsConfigured() {
        String url = InformixAgent.buildJdbcUrl(
            new ConnectParams(
                "informix-host",
                9088,
                "sysmaster",
                "",
                "",
                "",
                ""
            )
        );

        Assertions.assertEquals(
            "jdbc:informix-sqli://informix-host:9088/sysmaster:INFORMIXSERVER=informix-host",
            url
        );
    }

    @Test
    void fallsBackToInformixServerNameWhenHostIsAnIpAddress() {
        String url = InformixAgent.buildJdbcUrl(
            new ConnectParams(
                "172.26.128.159",
                20013,
                "sysmaster",
                "",
                "",
                "",
                ""
            )
        );

        Assertions.assertEquals(
            "jdbc:informix-sqli://172.26.128.159:20013/sysmaster:INFORMIXSERVER=informix",
            url
        );
    }

    @Test
    void usesSysmasterWhenDatabaseIsBlank() {
        String url = InformixAgent.buildJdbcUrl(
            new ConnectParams(
                "informix-host",
                9088,
                "",
                "",
                "",
                "INFORMIXSERVER=informix",
                ""
            )
        );

        Assertions.assertEquals(
            "jdbc:informix-sqli://informix-host:9088/sysmaster:INFORMIXSERVER=informix",
            url
        );
    }

    @Test
    void extractsPrimaryKeyColumnNumbersFromInformixIndexParts() {
        Assertions.assertEquals(
            Set.of(1, 3, 5),
            InformixAgent.primaryKeyColumnNumbers(Arrays.asList(1, -3, 0, 5, null))
        );
    }

    @Test
    void listsDatabasesFromSysmasterCatalog() {
        Assertions.assertEquals("SELECT name FROM sysmaster:sysdatabases ORDER BY name", InformixAgent.databaseCatalogSql());
    }
}
