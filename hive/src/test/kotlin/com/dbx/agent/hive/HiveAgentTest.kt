package com.dbx.agent.hive

import kotlin.test.Test

class HiveAgentTest {
    @Test
    fun hiveJdbcStandaloneRuntimeClassesAreAvailable() {
        Class.forName("org.apache.hive.jdbc.HiveDriver")
        Class.forName("org.apache.hive.org.apache.thrift.protocol.TProtocol")
    }
}
