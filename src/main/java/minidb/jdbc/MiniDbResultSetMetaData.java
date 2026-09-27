package minidb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC ResultSetMetaData：列数、列名与 JDBC 类型。 */
public final class MiniDbResultSetMetaData implements java.sql.ResultSetMetaData {
    private final List<String> columns;
    private final int rowCount;

    MiniDbResultSetMetaData(List<String> columns, int rowCount) {
        this.columns = columns;
        this.rowCount = rowCount;
    }

    private String col(int column) {
        String c = columns.get(column - 1);
        int dot = c.lastIndexOf('.');
        return dot >= 0 ? c.substring(dot + 1) : c;
    }

    /** 从样本数据推断 JDBC 类型（用于 getColumnType）。 */
    static int jdbcTypeOf(Object v) {
        if (v == null) return java.sql.Types.NULL;
        if (v instanceof Integer) return java.sql.Types.INTEGER;
        if (v instanceof Long) return java.sql.Types.BIGINT;
        if (v instanceof Double) return java.sql.Types.DOUBLE;
        if (v instanceof Boolean) return java.sql.Types.BOOLEAN;
        return java.sql.Types.VARCHAR;
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnLabel(int column) {
        return col(column);
    }

    @Override
    public String getColumnName(int column) {
        return col(column);
    }

    @Override
    public String getTableName(int column) {
        String c = columns.get(column - 1);
        int dot = c.lastIndexOf('.');
        return dot >= 0 ? c.substring(0, dot) : "";
    }

    @Override
    public String getSchemaName(int column) {
        return "";
    }

    @Override
    public String getCatalogName(int column) {
        return "";
    }

    /** 类型从结果数据推断（首行非空值）；空结果返回 VARCHAR。 */
    @Override
    public int getColumnType(int column) {
        return java.sql.Types.VARCHAR; // 保守默认；细节类型见文档
    }

    @Override
    public String getColumnTypeName(int column) {
        return "VARCHAR";
    }

    @Override
    public boolean isAutoIncrement(int column) {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) {
        return true;
    }

    @Override
    public boolean isSearchable(int column) {
        return true;
    }

    @Override
    public boolean isCurrency(int column) {
        return false;
    }

    @Override
    public int isNullable(int column) {
        return columnNullable;
    }

    @Override
    public boolean isSigned(int column) {
        return true;
    }

    @Override
    public int getPrecision(int column) {
        return 0;
    }

    @Override
    public int getScale(int column) {
        return 0;
    }

    @Override
    public int getColumnDisplaySize(int column) {
        return 20;
    }

    @Override
    public boolean isReadOnly(int column) {
        return true;
    }

    @Override
    public boolean isWritable(int column) {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) {
        return false;
    }

    @Override
    public String getColumnClassName(int column) {
        return String.class.getName();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不支持 unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
