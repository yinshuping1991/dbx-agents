package com.dbx.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AgentProtocol {
    public static final int PROTOCOL_VERSION = 1;

    public static final String METHOD_HANDSHAKE = "handshake";

    public static final String CAPABILITY_CONNECT = "connect";
    public static final String CAPABILITY_TEST_CONNECTION = "test_connection";
    public static final String CAPABILITY_METADATA = "metadata";
    public static final String CAPABILITY_QUERY = "query";
    public static final String CAPABILITY_PAGED_QUERY = "paged_query";
    public static final String CAPABILITY_TRANSACTION = "transaction";
    public static final String CAPABILITY_DDL = "ddl";

    public static final List<String> CAPABILITIES = Collections.unmodifiableList(Arrays.asList(
        CAPABILITY_CONNECT,
        CAPABILITY_TEST_CONNECTION,
        CAPABILITY_METADATA,
        CAPABILITY_QUERY,
        CAPABILITY_PAGED_QUERY,
        CAPABILITY_TRANSACTION,
        CAPABILITY_DDL
    ));

    private AgentProtocol() {
    }

    public static HandshakeResult handshakeResult() {
        return new HandshakeResult(PROTOCOL_VERSION, PROTOCOL_VERSION, CAPABILITIES);
    }

    public static final class HandshakeResult {
        private final int protocolVersion;
        private final int agentProtocolVersion;
        private final List<String> capabilities;

        private HandshakeResult(int protocolVersion, int agentProtocolVersion, List<String> capabilities) {
            this.protocolVersion = protocolVersion;
            this.agentProtocolVersion = agentProtocolVersion;
            this.capabilities = capabilities;
        }
    }
}
