package com.dbx.agent;

import java.util.Objects;

public final class ConnectParams {
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private String url_params;
    private String connection_string;
    private boolean mysql_compat_mode;

    public ConnectParams() {
        this("", 0, "", "", "", "", "", false);
    }

    public ConnectParams(
        String host,
        int port,
        String database,
        String username,
        String password,
        String url_params,
        String connection_string,
        boolean mysql_compat_mode
    ) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.url_params = url_params;
        this.connection_string = connection_string;
        this.mysql_compat_mode = mysql_compat_mode;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getUrl_params() {
        return url_params;
    }

    public String getConnection_string() {
        return connection_string;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setUrl_params(String url_params) {
        this.url_params = url_params;
    }

    public void setConnection_string(String connection_string) {
        this.connection_string = connection_string;
    }

    public boolean isMysql_compat_mode() {
        return mysql_compat_mode;
    }

    public void setMysql_compat_mode(boolean mysql_compat_mode) {
        this.mysql_compat_mode = mysql_compat_mode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConnectParams)) return false;
        ConnectParams that = (ConnectParams) other;
        return port == that.port
            && Objects.equals(host, that.host)
            && Objects.equals(database, that.database)
            && Objects.equals(username, that.username)
            && Objects.equals(password, that.password)
            && Objects.equals(url_params, that.url_params)
            && Objects.equals(connection_string, that.connection_string)
            && mysql_compat_mode == that.mysql_compat_mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, database, username, password, url_params, connection_string, mysql_compat_mode);
    }

    @Override
    public String toString() {
        return "ConnectParams(host=" + host
            + ", port=" + port
            + ", database=" + database
            + ", username=" + username
            + ", password=" + password
            + ", url_params=" + url_params
            + ", connection_string=" + connection_string
            + ", mysql_compat_mode=" + mysql_compat_mode
            + ")";
    }
}
