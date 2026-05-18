package com.dbx.agent.yashandb

import com.dbx.agent.ConfiguredJdbcAgent
import com.dbx.agent.JdbcAgentProfile
import com.dbx.agent.JsonRpcServer

class YashandbAgent : ConfiguredJdbcAgent(YASHANDB_PROFILE)

val YASHANDB_PROFILE = JdbcAgentProfile(
    driverClass = "com.yashandb.jdbc.Driver",
    urlTemplate = "jdbc:yasdb://{host}:{port}/{database}",
    defaultPort = 1688,
    skipExecutionContext = true,
)

fun main() {
    JsonRpcServer(YashandbAgent()).run()
}
