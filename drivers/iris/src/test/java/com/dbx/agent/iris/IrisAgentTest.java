package com.dbx.agent.iris;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrisAgentTest {
    @Test
    void skipsSchemaSwitchingBecauseIrisRejectsSetSchemaContext() {
        IrisAgent agent = new IrisAgent();

        assertEquals("", agent.setSchemaSQL("Ens"));
    }

    @Test
    void dedupesSchemasCaseInsensitively() {
        assertEquals(
            Arrays.asList("APP", "SQLUSER", "z_user"),
            IrisAgent.dedupeCaseInsensitiveSchemas(Arrays.asList("APP", "SQLUSER", "SQLUser", "app", "z_user"))
        );
    }

    @Test
    void ignoresBlankSchemasWhenDeduping() {
        assertEquals(
            Collections.singletonList("SQLUSER"),
            IrisAgent.dedupeCaseInsensitiveSchemas(Arrays.asList("", " ", null, "SQLUSER"))
        );
    }
}
