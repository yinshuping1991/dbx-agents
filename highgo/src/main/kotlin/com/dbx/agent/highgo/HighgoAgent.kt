package com.dbx.agent.highgo

import com.dbx.agent.JsonRpcServer
import com.dbx.agent.PostgresLikeAgent
import com.dbx.agent.PostgresLikeAgentProfile

class HighgoAgent : PostgresLikeAgent(HIGHGO_PROFILE)

val HIGHGO_PROFILE = PostgresLikeAgentProfile(
    driverClass = "com.highgo.jdbc.Driver",
    urlTemplate = "jdbc:highgo://{host}:{port}/{database}",
)

fun main() {
    JsonRpcServer(HighgoAgent()).run()
}
