package com.dbx.agent;

import java.util.Objects;

public final class ExecuteQueryOptions {
    private int maxRows;
    private Integer fetchSize;

    public ExecuteQueryOptions() {
        this(JdbcExecutor.DEFAULT_MAX_ROWS, null);
    }

    public ExecuteQueryOptions(int maxRows, Integer fetchSize) {
        this.maxRows = maxRows;
        this.fetchSize = fetchSize;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public Integer getFetchSize() {
        return fetchSize;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public void setFetchSize(Integer fetchSize) {
        this.fetchSize = fetchSize;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ExecuteQueryOptions)) return false;
        ExecuteQueryOptions that = (ExecuteQueryOptions) other;
        return maxRows == that.maxRows && Objects.equals(fetchSize, that.fetchSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRows, fetchSize);
    }

    @Override
    public String toString() {
        return "ExecuteQueryOptions(maxRows=" + maxRows + ", fetchSize=" + fetchSize + ")";
    }
}
