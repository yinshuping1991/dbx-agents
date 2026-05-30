package com.dbx.agent.xugu;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

public final class XuguAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile XUGU_PROFILE = new JdbcAgentProfile(
        "com.xugu.cloudjdbc.Driver",
        "jdbc:xugu://{host}:{port}/{database}",
        5138
    );

    public XuguAgent() {
        super(XUGU_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new XuguAgent()).run();
    }
}
