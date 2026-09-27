package minidb.storage;

import minidb.common.MiniDbException;

/** 列类型。固定长度类型用于定长记录表，VARCHAR 用于变长记录表。 */
public enum ColumnType {
    INT(4), BIGINT(8), DOUBLE(8), VARCHAR(-1);

    public final int fixedSize; // -1 表示变长

    ColumnType(int fixedSize) { this.fixedSize = fixedSize; }

    public static ColumnType of(String name) {
        return switch (name.toLowerCase()) {
            case "int", "integer" -> INT;
            case "bigint", "long" -> BIGINT;
            case "double", "float" -> DOUBLE;
            case "varchar", "string" -> VARCHAR;
            default -> throw new MiniDbException(MiniDbException.Code.SCHEMA, "未知类型: " + name);
        };
    }

    public void validate(Object v) {
        if (v == null) return;
        switch (this) {
            case INT -> {
                if (!(v instanceof Integer)) throw typeErr(v);
            }
            case BIGINT -> {
                if (!(v instanceof Long) && !(v instanceof Integer)) throw typeErr(v);
            }
            case DOUBLE -> {
                if (!(v instanceof Double) && !(v instanceof Number)) throw typeErr(v);
            }
            case VARCHAR -> {
                if (!(v instanceof String)) throw typeErr(v);
            }
        }
    }

    private MiniDbException typeErr(Object v) {
        return new MiniDbException(MiniDbException.Code.RECORD,
                name() + " 列不接受值 " + v + " (" + v.getClass().getSimpleName() + ")");
    }
}
