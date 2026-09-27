package minidb.storage;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RowCodec / Schema：编码边界与故障注入 */
class RowCodecTest {
    private static final Schema FIXED = new Schema("ft", List.of(
            Column.fixed("id", ColumnType.INT),
            Column.fixed("balance", ColumnType.BIGINT),
            Column.fixed("ratio", ColumnType.DOUBLE)));

    private static final Schema VAR = new Schema("vt", List.of(
            Column.fixed("id", ColumnType.INT),
            new Column("name", ColumnType.VARCHAR, 10)));

    @Test
    void fixedRoundtrip() {
        RowCodec c = new RowCodec(FIXED);
        Object[] row = {7, 123456789012345L, 2.5};
        assertArrayEquals(row, c.decode(c.encode(row), 0, c.encode(row).length));
    }

    @Test
    void fixedRecordSizeMatchesLayout() {
        assertEquals(4 + 8 + 8, FIXED.fixedRecordSize());
        assertEquals(-1, VAR.fixedRecordSize());
        assertTrue(FIXED.fixedLength());
        assertFalse(VAR.fixedLength());
    }

    @Test
    void varcharRoundtrip() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, "abc"};
        byte[] enc = c.encode(row);
        assertEquals(4 + 2 + 3, enc.length);
        assertArrayEquals(row, c.decode(enc, 0, enc.length));
    }

    @Test
    void unicodeVarcharRoundtrip() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, "中文abc"};
        byte[] enc = c.encode(row);
        assertArrayEquals(row, c.decode(enc, 0, enc.length));
    }

    @Test
    void emptyVarcharRoundtrip() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, ""};
        byte[] enc = c.encode(row);
        assertEquals(4 + 2, enc.length);
        assertArrayEquals(row, c.decode(enc, 0, enc.length));
    }

    @Test
    void varcharExactlyAtLimit() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, "abcdefghij"}; // 恰好 10 字节
        assertArrayEquals(row, c.decode(c.encode(row), 0, 16));
    }

    @Test
    void varcharOverLimitThrows() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, "abcdefghijk"}; // 11 字节 > 10
        MiniDbException e = assertThrows(MiniDbException.class, () -> c.encode(row));
        assertEquals(MiniDbException.Code.RECORD, e.code);
    }

    @Test
    void unicodeLengthCountedInBytes() {
        RowCodec c = new RowCodec(VAR);
        Object[] row = {1, "中文中文中文中文中文中文"}; // 12 汉字 = 36 字节 > 10
        assertThrows(MiniDbException.class, () -> c.encode(row));
    }

    @Test
    void wrongValueCountThrows() {
        RowCodec c = new RowCodec(FIXED);
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{1, 2L}));
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{1, 2L, 3.0, 4}));
    }

    @Test
    void nullRejected() {
        RowCodec c = new RowCodec(VAR);
        assertThrows(NullPointerException.class, () -> c.encode(new Object[]{1, null}));
    }

    @Test
    void typeMismatchThrows() {
        RowCodec c = new RowCodec(FIXED);
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{"s", 2L, 3.0}));
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{1, 2.5, 3.0}));
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{1, 2L, "x"}));
    }

    @Test
    void decodeCorruptTruncatedRecordThrows() {
        RowCodec c = new RowCodec(FIXED);
        byte[] enc = c.encode(new Object[]{1, 2L, 3.0});
        assertThrows(MiniDbException.class, () -> c.decode(enc, 0, enc.length - 3));
    }

    @Test
    void decodeWithOffset() {
        RowCodec c = new RowCodec(FIXED);
        byte[] enc = c.encode(new Object[]{5, 6L, 7.0});
        byte[] buf = new byte[10 + enc.length];
        System.arraycopy(enc, 0, buf, 10, enc.length);
        Object[] row = c.decode(buf, 10, enc.length);
        assertEquals(5, row[0]);
        assertEquals(6L, row[1]);
        assertEquals(7.0, row[2]);
    }

    @Test
    void intExtremeValuesRoundtrip() {
        RowCodec c = new RowCodec(FIXED);
        Object[] row = {Integer.MAX_VALUE, Long.MIN_VALUE, -Double.MAX_VALUE};
        assertArrayEquals(row, c.decode(c.encode(row), 0, 20));
    }

    @Test
    void schemaEncodeDecodeRoundtrip() {
        byte[] enc = VAR.encode();
        Schema back = Schema.decode(enc);
        assertEquals(VAR.tableName(), back.tableName());
        assertEquals(VAR.columns().size(), back.columns().size());
        assertEquals("name", back.columns().get(1).name());
        assertEquals(ColumnType.VARCHAR, back.columns().get(1).type());
        assertEquals(10, back.columns().get(1).maxLength());
    }

    @Test
    void schemaColumnLookup() {
        assertEquals(1, VAR.columnIndex("NAME")); // 大小写不敏感
        assertEquals(-1, VAR.columnIndex("nope"));
        assertThrows(MiniDbException.class, () -> VAR.column("nope"));
    }

    @Test
    void schemaValidation() {
        assertThrows(MiniDbException.class, () -> new Schema("t", List.of()));
        assertThrows(MiniDbException.class, () -> new Schema(" ", List.of(Column.fixed("a", ColumnType.INT))));
        assertThrows(MiniDbException.class, () -> new Column("v", ColumnType.VARCHAR, 0));
        assertThrows(MiniDbException.class, () -> new Column("v", ColumnType.VARCHAR, 3001));
        assertThrows(MiniDbException.class, () -> Column.fixed("v", ColumnType.VARCHAR));
    }

    @Test
    void unknownTypeRejected() {
        assertThrows(MiniDbException.class, () -> ColumnType.of("blob"));
        assertEquals(ColumnType.INT, ColumnType.of("INT"));
        assertEquals(ColumnType.VARCHAR, ColumnType.of("varchar"));
    }

    @Test
    void utf8ByteLengthUsedNotCharLength() {
        // 5 个汉字 = 15 字节，varchar(10) 拒绝；3 个汉字 = 9 字节通过
        RowCodec c = new RowCodec(VAR);
        assertThrows(MiniDbException.class, () -> c.encode(new Object[]{1, "汉字汉字汉字"}));
        Object[] ok = {1, "汉字汉"};
        byte[] enc = c.encode(ok);
        assertEquals(4 + 2 + 9, enc.length);
        assertArrayEquals(ok, c.decode(enc, 0, enc.length));
        // 确认编码里长度字段存的是字节数
        assertEquals(9, enc[4] & 0xFF);
        assertEquals(9, "汉字汉".getBytes(StandardCharsets.UTF_8).length);
    }
}
