package minidb.storage;

import minidb.common.MiniDbException;

/** 固定 4KB 页。dirty/pinCount/pageLsn 由 BufferPool 统一管理。 */
public final class Page {
    public static final int SIZE = 4096;

    public enum Type {
        FREE(0), META(1), BITMAP(2), FIXED_TABLE(3), VAR_TABLE(4), CATALOG(5),
        BTREE_INTERNAL(6), BTREE_LEAF(7);

        public final byte id;

        Type(int id) { this.id = (byte) id; }

        public static Type of(byte b) {
            for (Type t : values()) if (t.id == b) return t;
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "未知页类型: " + b);
        }
    }

    private final int pageId;
    private final byte[] data = new byte[SIZE];
    private boolean dirty;
    private int pinCount;
    private long pageLsn;

    Page(int pageId) {
        this.pageId = pageId;
    }

    public int pageId() { return pageId; }
    public byte[] data() { return data; }
    public boolean isDirty() { return dirty; }
    public int pinCount() { return pinCount; }
    public long pageLsn() { return pageLsn; }

    public void setDirty(boolean d) { this.dirty = d; }
    void incPin() { pinCount++; }
    void decPin() {
        if (pinCount <= 0)
            throw new MiniDbException(MiniDbException.Code.BUFFER_FULL,
                    "页 " + pageId + " unpin 次数超过 pin 次数");
        pinCount--;
    }
    /** 写入页 LSN（max 语义：并发修改同页时只前进不回退），并写穿透到页头字节。 */
    public void setPageLsn(long lsn) {
        if (lsn > pageLsn) {
            pageLsn = lsn;
            minidb.common.Bytes.putLong(data, TablePageHeader.OFF_PAGE_LSN, pageLsn);
            dirty = true;
        }
    }

    void loadFrom(byte[] src, int len) {
        java.util.Arrays.fill(data, (byte) 0);
        System.arraycopy(src, 0, data, 0, len);
        dirty = false;
        pageLsn = minidb.common.Bytes.getLong(data, TablePageHeader.OFF_PAGE_LSN);
    }

    public Type type() {
        return Type.of(data[TablePageHeader.OFF_TYPE]);
    }
}
