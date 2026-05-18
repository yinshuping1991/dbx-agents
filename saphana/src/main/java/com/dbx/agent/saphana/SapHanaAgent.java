package com.dbx.agent.saphana;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

public final class SapHanaAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile SAPHANA_PROFILE = new JdbcAgentProfile(
        "com.sap.db.jdbc.Driver",
        "jdbc:sap://{host}:{port}/?databaseName={database}",
        30015
    );

    public SapHanaAgent() {
        super(SAPHANA_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new SapHanaAgent()).run();
    }
}
