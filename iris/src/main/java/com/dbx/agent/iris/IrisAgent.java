package com.dbx.agent.iris;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

public final class IrisAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile IRIS_PROFILE = new JdbcAgentProfile(
        "com.intersystems.jdbc.IRISDriver",
        "jdbc:IRIS://{host}:{port}/{database}",
        1972
    );

    public IrisAgent() {
        super(IRIS_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new IrisAgent()).run();
    }
}
