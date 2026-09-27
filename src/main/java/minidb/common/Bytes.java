package minidb.common;

/** 页内字节编解码助手，统一小端序。所有落盘的多字节整数都走这里。 */
public final class Bytes {
    private Bytes() {}

    public static short getShort(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    public static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    public static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    public static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    public static long getLong(byte[] b, int off) {
        return (getInt(b, off) & 0xFFFFFFFFL) | ((long) getInt(b, off + 4) << 32);
    }

    public static void putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) v);
        putInt(b, off + 4, (int) (v >>> 32));
    }

    public static void copy(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
        System.arraycopy(src, srcOff, dst, dstOff, len);
    }
}
