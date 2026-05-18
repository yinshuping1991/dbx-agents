package com.dbx.agent.test;

import java.sql.Connection;

public final class setPrivateConnection {
    public setPrivateConnection(Object target, Connection connection) {
        TestSupport.setPrivateConnection(target, connection);
    }
}
