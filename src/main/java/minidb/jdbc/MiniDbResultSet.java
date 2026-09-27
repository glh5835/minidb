package minidb.jdbc;

import minidb.exec.Executor;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/** JDBC ResultSet：物化的查询结果，仅支持 FORWARD_ONLY 顺序读取。 */
public final class MiniDbResultSet extends AbstractUnsupportedResultSet {
    private final MiniDbStatement stmt;
    private final List<String> columns;
    private final List<Object[]> rows;
    private int pos = -1; // next() 前游标在第一行之前
    private boolean wasNull;
    private boolean closed;
    private Object[] current;

    MiniDbResultSet(MiniDbStatement stmt, Executor.Result result) {
        this.stmt = stmt;
        this.columns = result.columns();
        this.rows = result.rows();
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("ResultSet 已关闭");
    }

    private Object[] row() throws SQLException {
        ensureOpen();
        if (pos < 0 || pos >= rows.size())
            throw new SQLException("游标不在有效行上（先调用 next()）");
        return current;
    }

    @Override
    public boolean next() {
        if (closed) return false;
        pos++;
        if (pos < rows.size()) {
            current = rows.get(pos);
            return true;
        }
        current = null;
        return false;
    }

    @Override
    public void close() {
        closed = true;
        if (stmt != null) stmt.statementClosed(this);
    }

    @Override
    public boolean wasNull() {
        return wasNull;
    }

    private Object value(int columnIndex) throws SQLException {
        Object v = row()[columnIndex - 1];
        wasNull = v == null;
        return v;
    }

    private int resolve(String columnLabel) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            int dot = c.lastIndexOf('.');
            String bare = dot >= 0 ? c.substring(dot + 1) : c;
            if (bare.equalsIgnoreCase(columnLabel) || c.equalsIgnoreCase(columnLabel)) return i + 1;
        }
        throw new SQLException("未知列: " + columnLabel);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return Boolean.parseBoolean(v.toString());
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getInt(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getInt(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        if (v == null) return 0;
        if (v instanceof Number n) return (int) n.longValue();
        return Integer.parseInt(v.toString().trim());
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString().trim());
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString().trim());
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : new BigDecimal(v.toString());
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        throw new SQLException("不支持二进制列");
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        throw new SQLException("不支持 DATE 类型");
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        throw new SQLException("不支持 TIME 类型");
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        throw new SQLException("不支持 TIMESTAMP 类型");
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        Object v = value(columnIndex);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        throw new SQLException("类型不匹配: " + type);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(resolve(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(resolve(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(resolve(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(resolve(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(resolve(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(resolve(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(resolve(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(resolve(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(resolve(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(resolve(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(resolve(columnLabel), type);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return resolve(columnLabel);
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() {
        return new MiniDbResultSetMetaData(columns, rows.size());
    }

    @Override
    public boolean isBeforeFirst() {
        return !closed && pos < 0;
    }

    @Override
    public boolean isAfterLast() {
        return !closed && rows.size() > 0 && pos >= rows.size();
    }

    @Override
    public boolean isFirst() {
        return pos == 0;
    }

    @Override
    public boolean isLast() {
        return rows.size() > 0 && pos == rows.size() - 1;
    }

    @Override
    public int getRow() {
        return pos < 0 || pos >= rows.size() ? 0 : pos + 1;
    }

    @Override
    public java.sql.Statement getStatement() {
        return stmt;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不支持 unwrap: " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
