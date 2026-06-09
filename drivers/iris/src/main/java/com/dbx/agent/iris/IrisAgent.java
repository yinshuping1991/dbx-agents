package com.dbx.agent.iris;

import com.dbx.agent.ConfiguredJdbcAgent;
import com.dbx.agent.JdbcAgentProfile;
import com.dbx.agent.JsonRpcServer;
import com.dbx.agent.StandardJdbcMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IrisAgent extends ConfiguredJdbcAgent {
    public static final JdbcAgentProfile IRIS_PROFILE = new JdbcAgentProfile(
        "com.intersystems.jdbc.IRISDriver",
        "jdbc:IRIS://{host}:{port}/{database}",
        1972,
        true
    );

    public IrisAgent() {
        super(IRIS_PROFILE);
    }

    @Override
    public List<String> listSchemas() {
        return dedupeCaseInsensitiveSchemas(StandardJdbcMetadata.INSTANCE.listSchemas(requireConnection(), IRIS_PROFILE));
    }

    static List<String> dedupeCaseInsensitiveSchemas(List<String> schemas) {
        Map<String, String> byNormalizedName = new LinkedHashMap<>();
        for (String schema : schemas) {
            if (schema == null || schema.trim().isEmpty()) {
                continue;
            }
            String name = schema.trim();
            byNormalizedName.putIfAbsent(name.toUpperCase(Locale.ROOT), name);
        }
        return new ArrayList<>(byNormalizedName.values());
    }

    public static void main(String[] args) {
        new JsonRpcServer(new IrisAgent()).run();
    }
}
