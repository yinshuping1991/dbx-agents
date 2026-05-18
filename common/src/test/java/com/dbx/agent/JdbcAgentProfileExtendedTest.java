package com.dbx.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcAgentProfileExtendedTest {
    @Test
    void appendsQueryParamsToJdbcUrlTemplates() {
        JdbcAgentProfile profile = new JdbcAgentProfile(
            "example.Driver",
            "jdbc:example://{host}:{port}/{database}",
            1234
        );
        ConnectParams params = new ConnectParams();
        params.setHost("db.example.com");
        params.setPort(0);
        params.setDatabase("analytics");
        params.setUrl_params("ssl=true");

        assertEquals("jdbc:example://db.example.com:1234/analytics?ssl=true", profile.buildUrl(params));
    }

    @Test
    void preservesExplicitConnectionStringForVendorSpecificUrls() {
        JdbcAgentProfile profile = new JdbcAgentProfile(
            "example.Driver",
            "jdbc:example://{host}:{port}/{database}",
            1234
        );
        ConnectParams params = new ConnectParams();
        params.setConnection_string("jdbc:example:special://host/path;token=secret");

        assertEquals("jdbc:example:special://host/path;token=secret", profile.buildUrl(params));
    }
}
