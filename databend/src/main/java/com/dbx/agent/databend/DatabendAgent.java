package com.dbx.agent.databend;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;

import java.util.Arrays;
import java.util.Collections;

public final class DatabendAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile DATABEND_PROFILE = new JdbcAgentProfile(
        "com.databend.jdbc.DatabendDriver",
        "jdbc:databend://{host}:{port}/{database}",
        8000,
        false,
        Collections.singleton("INFORMATION_SCHEMA"),
        Arrays.asList("TABLE", "VIEW", "BASE TABLE"),
        "`",
        "USE",
        true,
        false,
        false,
        false
    );

    public DatabendAgent() {
        super(DATABEND_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new DatabendAgent()).run();
    }
}
