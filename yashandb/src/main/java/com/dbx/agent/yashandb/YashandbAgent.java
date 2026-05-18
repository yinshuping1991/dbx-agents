package com.dbx.agent.yashandb;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;
import java.util.Arrays;
import java.util.Collections;

public final class YashandbAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile YASHANDB_PROFILE = new JdbcAgentProfile(
        "com.yashandb.jdbc.Driver",
        "jdbc:yasdb://{host}:{port}/{database}",
        1688,
        true,
        Collections.emptySet(),
        Arrays.asList("TABLE", "VIEW", "MATERIALIZED VIEW", "SYSTEM TABLE", "SYSTEM VIEW")
    );

    public YashandbAgent() {
        super(YASHANDB_PROFILE);
    }

    public static void main(String[] args) {
        new JsonRpcServer(new YashandbAgent()).run();
    }
}
