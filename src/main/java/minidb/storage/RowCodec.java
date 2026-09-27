package minidb.storage;

import minidb.common.MiniDbException;

import java.nio.charset.StandardCharsets;

/** 行编解码：把一行值（Object[]）与字节序列互转。所有类型小端序。 */
public final class RowCodec {
    private final Schema schema;

    public RowCodec(Schema schema) {
        this.schema = schema;
    }

    /** 编码整行；VARCHAR 超长抛 RECORD 异常（故障注入点）。 */
    public byte[] encode(Object[] row) {
        if (row == null || row.length != schema.columns().size())
            throw new MiniDbException(MiniDbException.Code.RECORD,
                    "行值数量与列数不符: 期望 " + schema.columns().size());
        int size = 0;
        byte[][] enc = new byte[row.length][];
        for (int i = 0; i < row.length; i++) {
            enc[i] = encodeValue(schema.columns().get(i), row[i]);
            size += enc[i].length;
        }
        byte[] out = new byte[size];
        int off = 0;
        for (byte[] e : enc) {
            System.arraycopy(e, 0, out, off, e.length);
            off += e.length;
        }
        return out;
    }

    private byte[] encodeValue(Column col, Object v) {
        if (v != null) col.type().validate(v);
        return switch (col.type()) {
            case INT -> {
                int x = ((Number) java.util.Objects.requireNonNull(v, "INT 列不接受 NULL")).intValue();
                yield new byte[]{(byte) x, (byte) (x >> 8), (byte) (x >> 16), (byte) (x >> 24)};
            }
            case BIGINT -> {
                long x = ((Number) java.util.Objects.requireNonNull(v, "BIGINT 列不接受 NULL")).longValue();
                byte[] b = new byte[8];
                minidb.common.Bytes.putLong(b, 0, x);
                yield b;
            }
            case DOUBLE -> {
                double x = ((Number) java.util.Objects.requireNonNull(v, "DOUBLE 列不接受 NULL")).doubleValue();
                byte[] b = new byte[8];
                minidb.common.Bytes.putLong(b, 0, Double.doubleToLongBits(x));
                yield b;
            }
            case VARCHAR -> {
                String s = (String) java.util.Objects.requireNonNull(v, "VARCHAR 列不接受 NULL");
                byte[] sb = s.getBytes(StandardCharsets.UTF_8);
                if (sb.length > col.maxLength())
                    throw new MiniDbException(MiniDbException.Code.RECORD,
                            "值超出 VARCHAR(" + col.maxLength() + ") 上限: " + sb.length + " 字节");
                byte[] b = new byte[2 + sb.length];
                minidb.common.Bytes.putShort(b, 0, (short) sb.length);
                System.arraycopy(sb, 0, b, 2, sb.length);
                yield b;
            }
        };
    }

    /** 从 buf[off, off+len) 解码一行。 */
    public Object[] decode(byte[] buf, int off, int len) {
        Object[] row = new Object[schema.columns().size()];
        int end = off + len;
        int p = off;
        for (int i = 0; i < row.length; i++) {
            Column col = schema.columns().get(i);
            switch (col.type()) {
                case INT -> {
                    p += 4;
                    if (p > end) throw badLen();
                    row[i] = minidb.common.Bytes.getInt(buf, p - 4);
                }
                case BIGINT -> {
                    p += 8;
                    if (p > end) throw badLen();
                    row[i] = minidb.common.Bytes.getLong(buf, p - 8);
                }
                case DOUBLE -> {
                    p += 8;
                    if (p > end) throw badLen();
                    row[i] = Double.longBitsToDouble(minidb.common.Bytes.getLong(buf, p - 8));
                }
                case VARCHAR -> {
                    if (p + 2 > end) throw badLen();
                    int sl = minidb.common.Bytes.getShort(buf, p);
                    p += 2 + sl;
                    if (p > end) throw badLen();
                    row[i] = new String(buf, p - sl, sl, StandardCharsets.UTF_8);
                }
            }
        }
        return row;
    }

    private MiniDbException badLen() {
        return new MiniDbException(MiniDbException.Code.RECORD, "记录字节损坏：长度越界");
    }
}
