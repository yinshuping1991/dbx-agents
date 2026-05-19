package com.dbx.agent.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class MongoAgentTest {
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
}
