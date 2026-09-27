package minidb.storage;

import minidb.common.MiniDbException;

/** 列定义。VARCHAR 的 maxLength 是 UTF-8 字节上限（1..3000）。 */
public record Column(String name, ColumnType type, int maxLength) {
    public static final int MAX_VARCHAR = 3000;

    public Column {
        if (name == null || name.isBlank())
            throw new MiniDbException(MiniDbException.Code.SCHEMA, "列名不能为空");
        if (type == ColumnType.VARCHAR) {
            if (maxLength <= 0 || maxLength > MAX_VARCHAR)
                throw new MiniDbException(MiniDbException.Code.SCHEMA,
                        "VARCHAR 长度必须在 1.." + MAX_VARCHAR + " 之间: " + maxLength);
        }
    }

    public static Column fixed(String name, ColumnType type) {
        if (type == ColumnType.VARCHAR)
            throw new MiniDbException(MiniDbException.Code.SCHEMA, "VARCHAR 需要显式长度");
        return new Column(name, type, 0);
    }

    public boolean isFixed() { return type.fixedSize > 0; }
}
