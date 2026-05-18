package com.dbx.agent.vastbase

import com.dbx.agent.JsonRpcServer
import com.dbx.agent.PostgresLikeAgent
import com.dbx.agent.PostgresLikeAgentProfile

class VastbaseAgent : PostgresLikeAgent(VASTBASE_PROFILE)

val VASTBASE_PROFILE = PostgresLikeAgentProfile(
    driverClass = "cn.com.vastbase.Driver",
    urlTemplate = "jdbc:vastbase://{host}:{port}/{database}",
)

fun main() {
    JsonRpcServer(VastbaseAgent()).run()
}
