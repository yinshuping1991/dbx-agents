package com.dbx.agent.test

import com.dbx.agent.DatabaseAgent

abstract class JdbcConnectedAgentTest {
    protected abstract fun createConnectedAgent(databaseName: String): DatabaseAgent

    protected fun withAgent(databaseName: String, block: (DatabaseAgent) -> Unit) {
        val agent = createConnectedAgent(databaseName)
        try {
            block(agent)
        } finally {
            agent.disconnect()
        }
    }
}
