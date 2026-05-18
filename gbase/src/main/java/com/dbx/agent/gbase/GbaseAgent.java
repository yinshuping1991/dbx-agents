package com.dbx.agent.gbase;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

public final class GbaseAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile GBASE_PROFILE = new JdbcAgentProfile(
        "com.gbase.jdbc.Driver",
        "jdbc:gbase://{host}:{port}/{database}",
        5258
    );

    public GbaseAgent() {
        super(GBASE_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new GbaseAgent()).run();
    }
}
