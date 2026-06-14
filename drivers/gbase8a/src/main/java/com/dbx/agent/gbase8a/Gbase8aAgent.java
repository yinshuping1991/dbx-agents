package com.dbx.agent.gbase8a;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

public final class Gbase8aAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile GBASE8A_PROFILE = new JdbcAgentProfile(
        "com.gbase.jdbc.Driver",
        "jdbc:gbase://{host}:{port}/{database}?useSSL=false",
        5258,
        false,
        java.util.Collections.emptySet(),
        java.util.Arrays.asList("TABLE", "VIEW", "BASE TABLE"),
        "`",
        "USE",
        true,
        false,
        false,
        false
    );

    public Gbase8aAgent() {
        super(GBASE8A_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new Gbase8aAgent()).run();
    }
}
