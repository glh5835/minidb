package minidb.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bytes 编解码边界测试 */
class BytesTest {

    @Test
    void shortRoundtrip() {
        byte[] b = new byte[2];
        for (int v : new int[]{0, 1, -1, 32767, -32768, 255, 256}) {
            Bytes.putShort(b, 0, (short) v);
            assertEquals((short) v, Bytes.getShort(b, 0));
        }
    }

    @Test
    void intRoundtrip() {
        byte[] b = new byte[4];
        for (int v : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678}) {
            Bytes.putInt(b, 0, v);
            assertEquals(v, Bytes.getInt(b, 0));
        }
    }

    @Test
    void longRoundtrip() {
        byte[] b = new byte[8];
        for (long v : new long[]{0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789ABCDEFL}) {
            Bytes.putLong(b, 0, v);
            assertEquals(v, Bytes.getLong(b, 0));
        }
    }

    @Test
    void doubleBitsRoundtrip() {
        byte[] b = new byte[8];
        for (double v : new double[]{0.0, -0.0, 90.5, Double.MAX_VALUE, Double.MIN_VALUE,
                Double.POSITIVE_INFINITY, Double.NaN}) {
            Bytes.putLong(b, 0, Double.doubleToLongBits(v));
            assertEquals(Double.doubleToLongBits(v), Bytes.getLong(b, 0));
        }
    }

    @Test
    void longLowHighHalvesIndependent() {
        // 回归：曾因 int 移位把高 32 位丢掉
        byte[] b = new byte[8];
        Bytes.putLong(b, 0, 0xDEADBEEFCAFEBABEL);
        assertEquals(0xDEADBEEFCAFEBABEL, Bytes.getLong(b, 0));
        assertEquals(0xCAFEBABE, Bytes.getInt(b, 0));
        assertEquals(0xDEADBEEF, Bytes.getInt(b, 4));
    }

    @Test
    void fieldsDoNotOverlap() {
        byte[] b = new byte[16];
        Bytes.putInt(b, 0, 1);
        Bytes.putInt(b, 4, 2);
        Bytes.putLong(b, 8, 3L);
        assertEquals(1, Bytes.getInt(b, 0));
        assertEquals(2, Bytes.getInt(b, 4));
        assertEquals(3L, Bytes.getLong(b, 8));
    }

    @Test
    void littleEndianLayout() {
        byte[] b = new byte[4];
        Bytes.putInt(b, 0, 0x01020304);
        assertEquals(0x04, b[0]);
        assertEquals(0x03, b[1]);
        assertEquals(0x02, b[2]);
        assertEquals(0x01, b[3]);
    }
}
