package minidb.jdbc;

import minidb.common.MiniDbException;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.NClob;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * JDBC PreparedStatement：词法级 ? 占位符绑定（引号感知、字符串自动转义），
 * 绑定后交由内部 Statement 执行。教学实现选择“参数文本化”而非二进制协议。
 * 高级类型（流/大对象/日期时间）显式不支持。
 */
public final class MiniDbPreparedStatement implements java.sql.PreparedStatement {
    private final MiniDbStatement stmt;
    private final String sql;
    private final Object[] params;
    private final boolean[] nullSet;
    private final List<String> batch = new ArrayList<>();

    MiniDbPreparedStatement(MiniDbConnection conn, String sql) {
        this.stmt = new MiniDbStatement(conn);
        this.sql = sql;
        this.params = new Object[countPlaceholders(sql)];
        this.nullSet = new boolean[params.length];
    }

    private static int countPlaceholders(String sql) {
        int n = 0;
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            else if (c == '?' && !inQuote) n++;
        }
        return n;
    }

    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof String s) return "'" + s.replace("'", "''") + "'";
        return v.toString();
    }

    private String boundSql(boolean allowPartial) throws SQLException {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int paramIdx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                out.append(c);
            } else if (c == '?' && !inQuote) {
                if (paramIdx >= params.length)
                    throw new SQLException("参数个数超出绑定数量");
                Object v = params[paramIdx++];
                if (v == null && !nullSet[paramIdx - 1] && !allowPartial)
                    throw new SQLException("参数 " + paramIdx + " 未绑定");
                out.append(literal(v));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void checkAllSet() throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null && !nullSet[i])
                throw new SQLException("参数 " + (i + 1) + " 未绑定");
        }
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkAllSet();
        return stmt.executeQuery(boundSql(true));
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkAllSet();
        return stmt.executeUpdate(boundSql(true));
    }

    @Override
    public boolean execute() throws SQLException {
        checkAllSet();
        return stmt.execute(boundSql(true));
    }

    private void set(int i, Object v) {
        if (i < 1 || i > params.length)
            throw new IndexOutOfBoundsException("参数下标越界: " + i);
        params[i - 1] = v;
        nullSet[i - 1] = v == null;
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) { set(parameterIndex, null); }

    @Override
    public void setBoolean(int parameterIndex, boolean x) { set(parameterIndex, x); }

    @Override
    public void setByte(int parameterIndex, byte x) { set(parameterIndex, x); }

    @Override
    public void setShort(int parameterIndex, short x) { set(parameterIndex, x); }

    @Override
    public void setInt(int parameterIndex, int x) { set(parameterIndex, x); }

    @Override
    public void setLong(int parameterIndex, long x) { set(parameterIndex, x); }

    @Override
    public void setFloat(int parameterIndex, float x) { set(parameterIndex, x); }

    @Override
    public void setDouble(int parameterIndex, double x) { set(parameterIndex, x); }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) { set(parameterIndex, x); }

    @Override
    public void setString(int parameterIndex, String x) { set(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x) { set(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) { set(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) { set(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) { set(parameterIndex, x); }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) { set(parameterIndex, x); }

    @Override
    public void clearParameters() {
        java.util.Arrays.fill(params, null);
        java.util.Arrays.fill(nullSet, false);
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        ResultSet rs = stmt.getResultSet();
        return rs != null ? rs.getMetaData() : null;
    }

    // ---------- 批处理（简化：文本化 SQL 队列） ----------

    @Override
    public void addBatch() {
        batch.add(boundSqlUnchecked());
    }

    @Override
    public void clearBatch() {
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int[] counts = new int[batch.size()];
        try {
            for (int i = 0; i < batch.size(); i++) counts[i] = stmt.executeUpdate(batch.get(i));
        } finally {
            batch.clear();
        }
        return counts;
    }

    private String boundSqlUnchecked() {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int paramIdx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                out.append(c);
            } else if (c == '?' && !inQuote) {
                Object v = paramIdx < params.length ? params[paramIdx++] : null;
                out.append(literal(v));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---------- 委托给内部 Statement ----------

    @Override
    public void close() throws SQLException { stmt.close(); }

    @Override
    public boolean isClosed() { return stmt.isClosed(); }

    @Override
    public java.sql.Connection getConnection() throws SQLException { return stmt.getConnection(); }

    @Override
    public ResultSet getResultSet() { return stmt.getResultSet(); }

    @Override
    public int getUpdateCount() throws SQLException { return stmt.getUpdateCount(); }

    @Override
    public boolean getMoreResults() { return stmt.getMoreResults(); }

    @Override
    public boolean getMoreResults(int current) { return stmt.getMoreResults(current); }

    @Override
    public int getMaxFieldSize() { return stmt.getMaxFieldSize(); }

    @Override
    public void setMaxFieldSize(int max) { stmt.setMaxFieldSize(max); }

    @Override
    public int getMaxRows() { return stmt.getMaxRows(); }

    @Override
    public void setMaxRows(int max) { stmt.setMaxRows(max); }

    @Override
    public void setEscapeProcessing(boolean enable) { stmt.setEscapeProcessing(enable); }

    @Override
    public int getQueryTimeout() { return stmt.getQueryTimeout(); }

    @Override
    public void setQueryTimeout(int seconds) { stmt.setQueryTimeout(seconds); }

    @Override
    public void cancel() { stmt.cancel(); }

    @Override
    public SQLWarning getWarnings() { return stmt.getWarnings(); }

    @Override
    public void clearWarnings() { stmt.clearWarnings(); }

    @Override
    public void setCursorName(String name) { stmt.setCursorName(name); }

    @Override
    public int getResultSetConcurrency() { return stmt.getResultSetConcurrency(); }

    @Override
    public int getResultSetType() { return stmt.getResultSetType(); }

    @Override
    public void setFetchDirection(int direction) { stmt.setFetchDirection(direction); }

    @Override
    public int getFetchDirection() { return stmt.getFetchDirection(); }

    @Override
    public void setFetchSize(int rows) { stmt.setFetchSize(rows); }

    @Override
    public int getFetchSize() { return stmt.getFetchSize(); }

    @Override
    public int getResultSetHoldability() { return stmt.getResultSetHoldability(); }

    @Override
    public ResultSet getGeneratedKeys() { return stmt.getGeneratedKeys(); }

    @Override
    public boolean isPoolable() { return stmt.isPoolable(); }

    @Override
    public void setPoolable(boolean poolable) { stmt.setPoolable(poolable); }

    @Override
    public void closeOnCompletion() { stmt.closeOnCompletion(); }

    @Override
    public boolean isCloseOnCompletion() { return stmt.isCloseOnCompletion(); }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不支持 unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        int[] counts = executeBatch();
        long[] out = new long[counts.length];
        for (int i = 0; i < counts.length; i++) out[i] = counts[i];
        return out;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        return executeUpdate();
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        return stmt.getLargeUpdateCount();
    }

    @Override
    public void setLargeMaxRows(long max) { stmt.setMaxRows((int) Math.min(max, Integer.MAX_VALUE)); }

    @Override
    public long getLargeMaxRows() { return stmt.getMaxRows(); }

    // ---------- 显式不支持的高级类型 ----------
    private UnsupportedOperationException uns() {
        return new UnsupportedOperationException("MiniDB 不支持该参数类型");
    }

    @Override public void setNull(int i, int t, String typeName) { set(i, null); }
    @Override public void setBytes(int i, byte[] x) { throw uns(); }
    @Override public void setDate(int i, java.sql.Date x) { throw uns(); }
    @Override public void setTime(int i, java.sql.Time x) { throw uns(); }
    @Override public void setTimestamp(int i, Timestamp x) { throw uns(); }
    @Override public void setAsciiStream(int i, InputStream x, int length) { throw uns(); }
    @Override public void setUnicodeStream(int i, InputStream x, int length) { throw uns(); }
    @Override public void setBinaryStream(int i, InputStream x, int length) { throw uns(); }
    @Override public void setCharacterStream(int i, Reader x, int length) { throw uns(); }
    @Override public void setRef(int i, java.sql.Ref x) { throw uns(); }
    @Override public void setBlob(int i, java.sql.Blob x) { throw uns(); }
    @Override public void setClob(int i, java.sql.Clob x) { throw uns(); }
    @Override public void setArray(int i, java.sql.Array x) { throw uns(); }
    @Override public void setDate(int i, java.sql.Date x, Calendar cal) { throw uns(); }
    @Override public void setTime(int i, java.sql.Time x, Calendar cal) { throw uns(); }
    @Override public void setTimestamp(int i, Timestamp x, Calendar cal) { throw uns(); }
    @Override public void setURL(int i, java.net.URL x) { throw uns(); }
    @Override public java.sql.ParameterMetaData getParameterMetaData() { throw uns(); }
    @Override public void setRowId(int i, RowId x) { throw uns(); }
    @Override public void setNString(int i, String x) { throw uns(); }
    @Override public void setNCharacterStream(int i, Reader x, long length) { throw uns(); }
    @Override public void setNClob(int i, NClob x) { throw uns(); }
    @Override public void setClob(int i, Reader x, long length) { throw uns(); }
    @Override public void setBlob(int i, InputStream x, long length) { throw uns(); }
    @Override public void setNClob(int i, Reader x, long length) { throw uns(); }
    @Override public void setSQLXML(int i, java.sql.SQLXML x) { throw uns(); }
    @Override public void setAsciiStream(int i, InputStream x, long length) { throw uns(); }
    @Override public void setBinaryStream(int i, InputStream x, long length) { throw uns(); }
    @Override public void setCharacterStream(int i, Reader x, long length) { throw uns(); }
    @Override public void setAsciiStream(int i, InputStream x) { throw uns(); }
    @Override public void setBinaryStream(int i, InputStream x) { throw uns(); }
    @Override public void setCharacterStream(int i, Reader x) { throw uns(); }
    @Override public void setNCharacterStream(int i, Reader x) { throw uns(); }
    @Override public void setClob(int i, Reader x) { throw uns(); }
    @Override public void setBlob(int i, InputStream x) { throw uns(); }
    @Override public void setNClob(int i, Reader x) { throw uns(); }
    @Override public boolean execute(String sql) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public ResultSet executeQuery(String sql) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public int executeUpdate(String sql) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { throw new SQLException("PreparedStatement 不能改变 SQL"); }
    @Override public void addBatch(String sql) throws SQLException { throw new SQLException("PreparedStatement 使用 addBatch()"); }

}
