package minidb.storage;

import minidb.common.MiniDbException;

import java.util.ArrayList;
import java.util.List;

/** 表模式：列的有序集合。全部列都是定长类型时是定长记录表，否则是变长记录表。 */
public record Schema(String tableName, List<Column> columns) {
    public Schema {
        if (tableName == null || tableName.isBlank())
            throw new MiniDbException(MiniDbException.Code.SCHEMA, "表名不能为空");
        columns = List.copyOf(columns);
        if (columns.isEmpty())
            throw new MiniDbException(MiniDbException.Code.SCHEMA, "表至少需要一列");
    }

    public boolean fixedLength() {
        return columns.stream().allMatch(Column::isFixed);
    }

    /** 定长记录大小；含 VARCHAR 时返回 -1。 */
    public int fixedRecordSize() {
        int n = 0;
        for (Column c : columns) {
            if (!c.isFixed()) return -1;
            n += c.type().fixedSize;
        }
        return n;
    }

    public int columnIndex(String name) {
        for (int i = 0; i < columns.size(); i++)
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }

    public Column column(String name) {
        int i = columnIndex(name);
        if (i < 0) throw new MiniDbException(MiniDbException.Code.SCHEMA,
                "表 " + tableName + " 没有列 " + name);
        return columns.get(i);
    }

    /** 序列化到目录页。布局：[tableLen short][table][colCount short]{[nameLen short][name][type byte][maxLen int]} */
    public byte[] encode() {
        byte[] tb = utf8(tableName);
        byte[] buf = new byte[4096];
        int off = 0;
        minidb.common.Bytes.putShort(buf, off, (short) columns.size()); off += 2;
        minidb.common.Bytes.putShort(buf, off, (short) tb.length); off += 2;
        System.arraycopy(tb, 0, buf, off, tb.length); off += tb.length;
        for (Column c : columns) {
            byte[] nb = utf8(c.name());
            minidb.common.Bytes.putShort(buf, off, (short) nb.length); off += 2;
            System.arraycopy(nb, 0, buf, off, nb.length); off += nb.length;
            buf[off++] = (byte) c.type().name().charAt(0); // I/B/D/V
            minidb.common.Bytes.putInt(buf, off, c.maxLength()); off += 4;
        }
        byte[] out = new byte[off];
        System.arraycopy(buf, 0, out, 0, off);
        return out;
    }

    public static Schema decode(byte[] b) {
        int off = 0;
        int n = minidb.common.Bytes.getShort(b, off); off += 2;
        int tn = minidb.common.Bytes.getShort(b, off); off += 2;
        String tname = new String(b, off, tn, java.nio.charset.StandardCharsets.UTF_8); off += tn;
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int ln = minidb.common.Bytes.getShort(b, off); off += 2;
            String cname = new String(b, off, ln, java.nio.charset.StandardCharsets.UTF_8); off += ln;
            char t = (char) (b[off++] & 0xFF);
            int maxLen = minidb.common.Bytes.getInt(b, off); off += 4;
            ColumnType type = switch (t) {
                case 'I' -> ColumnType.INT;
                case 'B' -> ColumnType.BIGINT;
                case 'D' -> ColumnType.DOUBLE;
                case 'V' -> ColumnType.VARCHAR;
                default -> throw new MiniDbException(MiniDbException.Code.CATALOG, "坏列类型: " + t);
            };
            cols.add(new Column(cname, type, maxLen));
        }
        return new Schema(tname, cols);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
