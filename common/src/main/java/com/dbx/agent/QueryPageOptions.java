package com.dbx.agent;

import java.util.Objects;

public final class QueryPageOptions {
    private int pageSize;
    private Integer fetchSize;
    private int maxRows;

    public QueryPageOptions() {
        this(100, null, JdbcExecutor.DEFAULT_MAX_ROWS);
    }

    public QueryPageOptions(int pageSize, Integer fetchSize, int maxRows) {
        this.pageSize = pageSize;
        this.fetchSize = fetchSize;
        this.maxRows = maxRows;
    }

    public int getPageSize() {
        return pageSize;
    }

    public Integer getFetchSize() {
        return fetchSize;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public void setFetchSize(Integer fetchSize) {
        this.fetchSize = fetchSize;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof QueryPageOptions)) return false;
        QueryPageOptions that = (QueryPageOptions) other;
        return pageSize == that.pageSize
            && maxRows == that.maxRows
            && Objects.equals(fetchSize, that.fetchSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageSize, fetchSize, maxRows);
    }

    @Override
    public String toString() {
        return "QueryPageOptions(pageSize=" + pageSize + ", fetchSize=" + fetchSize + ", maxRows=" + maxRows + ")";
    }
}
