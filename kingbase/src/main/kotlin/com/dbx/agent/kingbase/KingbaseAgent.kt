package com.dbx.agent.kingbase

import com.dbx.agent.JsonRpcServer
import com.dbx.agent.PostgresLikeAgent
import com.dbx.agent.PostgresLikeAgentProfile

class KingbaseAgent : PostgresLikeAgent(KINGBASE_PROFILE)

val KINGBASE_PROFILE = PostgresLikeAgentProfile(
    driverClass = "com.kingbase8.Driver",
    urlTemplate = "jdbc:kingbase8://{host}:{port}/{database}",
)

fun main() {
    JsonRpcServer(KingbaseAgent()).run()
}
