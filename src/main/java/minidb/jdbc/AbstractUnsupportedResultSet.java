package minidb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.Ref;
import java.sql.NClob;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

/**
 * java.sql.ResultSet 的“显式不支持”适配基类：所有方法默认抛 SQLException，
 * 由 MiniDbResultSet 覆盖核心方法。教学实现明确声明不支持的范围。
 */
abstract class AbstractUnsupportedResultSet implements java.sql.ResultSet {
    SQLException uns() {
        return new SQLException("MiniDB 不支持该 ResultSet 特性");
    }

    // ---------- 定位（仅支持 FORWARD_ONLY 顺序读取） ----------
    @Override public boolean absolute(int row) throws SQLException { throw uns(); }
    @Override public boolean relative(int rows) throws SQLException { throw uns(); }
    @Override public boolean previous() throws SQLException { throw uns(); }
    @Override public void beforeFirst() throws SQLException { throw uns(); }
    @Override public void afterLast() throws SQLException { throw uns(); }
    @Override public boolean first() throws SQLException { throw uns(); }
    @Override public boolean last() throws SQLException { throw uns(); }

    // ---------- 更新（只读结果集） ----------
    @Override public void updateNull(int i) throws SQLException { throw uns(); }
    @Override public void updateBoolean(int i, boolean x) throws SQLException { throw uns(); }
    @Override public void updateByte(int i, byte x) throws SQLException { throw uns(); }
    @Override public void updateShort(int i, short x) throws SQLException { throw uns(); }
    @Override public void updateInt(int i, int x) throws SQLException { throw uns(); }
    @Override public void updateLong(int i, long x) throws SQLException { throw uns(); }
    @Override public void updateFloat(int i, float x) throws SQLException { throw uns(); }
    @Override public void updateDouble(int i, double x) throws SQLException { throw uns(); }
    @Override public void updateBigDecimal(int i, BigDecimal x) throws SQLException { throw uns(); }
    @Override public void updateString(int i, String x) throws SQLException { throw uns(); }
    @Override public void updateBytes(int i, byte[] x) throws SQLException { throw uns(); }
    @Override public void updateDate(int i, Date x) throws SQLException { throw uns(); }
    @Override public void updateTime(int i, Time x) throws SQLException { throw uns(); }
    @Override public void updateTimestamp(int i, Timestamp x) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(int i, InputStream x, int len) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(int i, InputStream x, int len) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(int i, Reader x, int len) throws SQLException { throw uns(); }
    @Override public void updateObject(int i, Object x, int scale) throws SQLException { throw uns(); }
    @Override public void updateObject(int i, Object x) throws SQLException { throw uns(); }
    @Override public void updateObject(int i, Object x, java.sql.SQLType t) throws SQLException { throw uns(); }
    @Override public void updateObject(int i, Object x, java.sql.SQLType t, int scale) throws SQLException { throw uns(); }
    @Override public void insertRow() throws SQLException { throw uns(); }
    @Override public void updateRow() throws SQLException { throw uns(); }
    @Override public void deleteRow() throws SQLException { throw uns(); }
    @Override public void refreshRow() throws SQLException { throw uns(); }
    @Override public void cancelRowUpdates() throws SQLException { throw uns(); }
    @Override public void moveToInsertRow() throws SQLException { throw uns(); }
    @Override public void moveToCurrentRow() throws SQLException { throw uns(); }

    // ---------- 按列名更新 ----------
    @Override public void updateNull(String l) throws SQLException { throw uns(); }
    @Override public void updateBoolean(String l, boolean x) throws SQLException { throw uns(); }
    @Override public void updateByte(String l, byte x) throws SQLException { throw uns(); }
    @Override public void updateShort(String l, short x) throws SQLException { throw uns(); }
    @Override public void updateInt(String l, int x) throws SQLException { throw uns(); }
    @Override public void updateLong(String l, long x) throws SQLException { throw uns(); }
    @Override public void updateFloat(String l, float x) throws SQLException { throw uns(); }
    @Override public void updateDouble(String l, double x) throws SQLException { throw uns(); }
    @Override public void updateBigDecimal(String l, BigDecimal x) throws SQLException { throw uns(); }
    @Override public void updateString(String l, String x) throws SQLException { throw uns(); }
    @Override public void updateBytes(String l, byte[] x) throws SQLException { throw uns(); }
    @Override public void updateDate(String l, Date x) throws SQLException { throw uns(); }
    @Override public void updateTime(String l, Time x) throws SQLException { throw uns(); }
    @Override public void updateTimestamp(String l, Timestamp x) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(String l, InputStream x, int len) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(String l, InputStream x, int len) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(String l, Reader x, int len) throws SQLException { throw uns(); }
    @Override public void updateObject(String l, Object x, int scale) throws SQLException { throw uns(); }
    @Override public void updateObject(String l, Object x) throws SQLException { throw uns(); }
    @Override public void updateObject(String l, Object x, java.sql.SQLType t) throws SQLException { throw uns(); }
    @Override public void updateObject(String l, Object x, java.sql.SQLType t, int scale) throws SQLException { throw uns(); }
    @Override public void updateArray(int i, java.sql.Array x) throws SQLException { throw uns(); }
    @Override public void updateRef(int i, java.sql.Ref x) throws SQLException { throw uns(); }
    @Override public void updateArray(String l, java.sql.Array x) throws SQLException { throw uns(); }
    @Override public void updateRef(String l, java.sql.Ref x) throws SQLException { throw uns(); }
    @Override public BigDecimal getBigDecimal(String l, int scale) throws SQLException { throw uns(); }
    @Override public byte[] getBytes(String l) throws SQLException { throw uns(); }
    @Override public BigDecimal getBigDecimal(int i, int scale) throws SQLException { throw uns(); }

    // ---------- 流 / 大对象 / 高级类型 ----------
    @Override public InputStream getAsciiStream(int i) throws SQLException { throw uns(); }
    @Override public InputStream getUnicodeStream(int i) throws SQLException { throw uns(); }
    @Override public InputStream getBinaryStream(int i) throws SQLException { throw uns(); }
    @Override public InputStream getAsciiStream(String l) throws SQLException { throw uns(); }
    @Override public InputStream getUnicodeStream(String l) throws SQLException { throw uns(); }
    @Override public InputStream getBinaryStream(String l) throws SQLException { throw uns(); }
    @Override public Reader getCharacterStream(int i) throws SQLException { throw uns(); }
    @Override public Reader getCharacterStream(String l) throws SQLException { throw uns(); }
    @Override public Ref getRef(int i) throws SQLException { throw uns(); }
    @Override public Ref getRef(String l) throws SQLException { throw uns(); }
    @Override public Blob getBlob(int i) throws SQLException { throw uns(); }
    @Override public Blob getBlob(String l) throws SQLException { throw uns(); }
    @Override public Clob getClob(int i) throws SQLException { throw uns(); }
    @Override public Clob getClob(String l) throws SQLException { throw uns(); }
    @Override public Array getArray(int i) throws SQLException { throw uns(); }
    @Override public Array getArray(String l) throws SQLException { throw uns(); }
    @Override public java.sql.RowId getRowId(int i) throws SQLException { throw uns(); }
    @Override public java.sql.RowId getRowId(String l) throws SQLException { throw uns(); }
    @Override public void updateRowId(int i, java.sql.RowId x) throws SQLException { throw uns(); }
    @Override public void updateRowId(String l, java.sql.RowId x) throws SQLException { throw uns(); }
    @Override public int getHoldability() { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public java.sql.NClob getNClob(int i) throws SQLException { throw uns(); }
    @Override public java.sql.NClob getNClob(String l) throws SQLException { throw uns(); }
    @Override public void updateNClob(int i, NClob x) throws SQLException { throw uns(); }
    @Override public void updateNClob(String l, NClob x) throws SQLException { throw uns(); }
    @Override public java.sql.SQLXML getSQLXML(int i) throws SQLException { throw uns(); }
    @Override public java.sql.SQLXML getSQLXML(String l) throws SQLException { throw uns(); }
    @Override public void updateSQLXML(int i, SQLXML x) throws SQLException { throw uns(); }
    @Override public void updateSQLXML(String l, SQLXML x) throws SQLException { throw uns(); }
    @Override public String getNString(int i) throws SQLException { throw uns(); }
    @Override public String getNString(String l) throws SQLException { throw uns(); }
    @Override public Reader getNCharacterStream(int i) throws SQLException { throw uns(); }
    @Override public Reader getNCharacterStream(String l) throws SQLException { throw uns(); }
    @Override public void updateNString(int i, String x) throws SQLException { throw uns(); }
    @Override public void updateNString(String l, String x) throws SQLException { throw uns(); }
    @Override public void updateNCharacterStream(int i, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateNCharacterStream(String l, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateNCharacterStream(int i, Reader x) throws SQLException { throw uns(); }
    @Override public void updateNCharacterStream(String l, Reader x) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(int i, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(int i, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(int i, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(String l, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(String l, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(String l, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(int i, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(int i, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(int i, Reader x) throws SQLException { throw uns(); }
    @Override public void updateAsciiStream(String l, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateBinaryStream(String l, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateCharacterStream(String l, Reader x) throws SQLException { throw uns(); }
    @Override public void updateBlob(int i, Blob x) throws SQLException { throw uns(); }
    @Override public void updateBlob(String l, Blob x) throws SQLException { throw uns(); }
    @Override public void updateBlob(int i, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateBlob(String l, InputStream x, long len) throws SQLException { throw uns(); }
    @Override public void updateBlob(int i, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateBlob(String l, InputStream x) throws SQLException { throw uns(); }
    @Override public void updateClob(int i, Clob x) throws SQLException { throw uns(); }
    @Override public void updateClob(String l, Clob x) throws SQLException { throw uns(); }
    @Override public void updateClob(int i, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateClob(String l, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateClob(int i, Reader x) throws SQLException { throw uns(); }
    @Override public void updateClob(String l, Reader x) throws SQLException { throw uns(); }
    @Override public void updateNClob(int i, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateNClob(String l, Reader x, long len) throws SQLException { throw uns(); }
    @Override public void updateNClob(int i, Reader x) throws SQLException { throw uns(); }
    @Override public void updateNClob(String l, Reader x) throws SQLException { throw uns(); }

    // ---------- 日期时间 ----------
    @Override public Date getDate(int i, Calendar c) throws SQLException { throw uns(); }
    @Override public Date getDate(String l) throws SQLException { throw uns(); }
    @Override public Date getDate(String l, Calendar c) throws SQLException { throw uns(); }
    @Override public Time getTime(int i, Calendar c) throws SQLException { throw uns(); }
    @Override public Time getTime(String l) throws SQLException { throw uns(); }
    @Override public Time getTime(String l, Calendar c) throws SQLException { throw uns(); }
    @Override public Timestamp getTimestamp(int i, Calendar c) throws SQLException { throw uns(); }
    @Override public Timestamp getTimestamp(String l) throws SQLException { throw uns(); }
    @Override public Timestamp getTimestamp(String l, Calendar c) throws SQLException { throw uns(); }
    @Override public URL getURL(int i) throws SQLException { throw uns(); }
    @Override public URL getURL(String l) throws SQLException { throw uns(); }

    // ---------- 其他 ----------
    @Override public Object getObject(int i, Map<String, Class<?>> m) throws SQLException { throw uns(); }
    @Override public Object getObject(String l, Map<String, Class<?>> m) throws SQLException { throw uns(); }
    @Override public String getCursorName() throws SQLException { throw uns(); }
    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { /* 无警告 */ }
    @Override public void setFetchDirection(int d) throws SQLException {
        if (d != FETCH_FORWARD) throw uns();
    }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) { /* 一次性物化 */ }
    @Override public int getFetchSize() { return 0; }
    @Override public int getType() { return TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
}
