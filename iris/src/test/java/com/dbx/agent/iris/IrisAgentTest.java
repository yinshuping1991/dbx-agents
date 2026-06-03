package com.dbx.agent.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrisAgentTest {
    @Test
    void skipsSchemaSwitchingBecauseIrisRejectsSetSchemaContext() {
        IrisAgent agent = new IrisAgent();

        assertEquals("", agent.setSchemaSQL("Ens"));
    }
}
