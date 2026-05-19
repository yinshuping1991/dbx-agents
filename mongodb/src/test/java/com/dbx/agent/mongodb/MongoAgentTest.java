package com.dbx.agent.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.agent.AgentProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MongoAgentTest {
    @Test
    void exposesProtocolHandshakeOverJsonRpc() {
        String response = MongoAgent.handleRequest(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"handshake\",\"params\":{\"appVersion\":\"0.5.13\",\"supportedProtocolVersions\":[1]}}"
        );

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertEquals(7, json.get("id").getAsInt());
        assertEquals(AgentProtocol.PROTOCOL_VERSION, result.get("protocolVersion").getAsInt());
        assertEquals(AgentProtocol.PROTOCOL_VERSION, result.get("agentProtocolVersion").getAsInt());
        assertTrue(containsCapability(result.getAsJsonArray("capabilities"), AgentProtocol.CAPABILITY_CONNECT));
        assertTrue(containsCapability(result.getAsJsonArray("capabilities"), AgentProtocol.CAPABILITY_QUERY));
        assertTrue(containsCapability(result.getAsJsonArray("capabilities"), AgentProtocol.CAPABILITY_METADATA));
    }

    @Test
    void usesAuthSourceFromUrlParamsAsAuthenticationDatabase() {
        JsonObject connection = new JsonObject();
        connection.addProperty("database", "gray_lite_twin_fat");
        connection.addProperty("url_params", "authSource=admin&authMechanism=SCRAM-SHA-1");

        assertEquals("admin", MongoAgent.authenticationDatabase(connection));
    }

    @Test
    void fallsBackToDefaultDatabaseWhenAuthSourceIsMissing() {
        JsonObject connection = new JsonObject();
        connection.addProperty("database", "gray_lite_twin_fat");

        assertEquals("gray_lite_twin_fat", MongoAgent.authenticationDatabase(connection));
    }

    private static boolean containsCapability(JsonArray capabilities, String expected) {
        for (int i = 0; i < capabilities.size(); i++) {
            if (expected.equals(capabilities.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }
}
