package minidb.storage;

import minidb.common.MiniDbException;
import minidb.common.Rid;

/**
 * 定长记录表：页内槽定长排列，末尾是槽占用位图。
 * 布局：[32B 头][记录 0..cap-1，每条 recordSize 字节][占用位图 ceil(cap/8) 字节]
 * 增删改查都在原页原槽完成，RID 永不变化。
 */
public final class FixedTable extends BaseTable {
    private final int recordSize;
    private final int capacity;
    private final int bitmapOff;

    public FixedTable(StorageEngine engine, Schema schema, int firstPage) {
        super(engine, schema, firstPage);
        int rs = schema.fixedRecordSize();
        if (rs <= 0)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "定长表不能含 VARCHAR 列: " + schema.tableName());
        this.recordSize = rs;
        int bitmapMax = Page.SIZE - TablePageHeader.HDR_SIZE; // 4064
        this.capacity = (bitmapMax * 8) / (8 * recordSize + 1);
        this.bitmapOff = Page.SIZE - (capacity + 7) / 8;
        rebuild();
    }

    @Override
    protected boolean fixedLayout() { return true; }

    @Override
    protected void initPageData(byte[] d) {
        TablePageHeader.init(d, Page.Type.FIXED_TABLE);
    }

    private int usedOf(byte[] d) {
        int used = 0;
        for (int i = 0; i < capacity; i++)
            if (bitGet(d, i)) used++;
        return used;
    }

    private boolean bitGet(byte[] d, int slot) {
        return (d[bitmapOff + slot / 8] & (1 << (slot % 8))) != 0;
    }

    private void bitSet(byte[] d, int slot, boolean on) {
        int off = bitmapOff + slot / 8;
        if (on) d[off] |= (byte) (1 << (slot % 8));
        else d[off] &= (byte) ~(1 << (slot % 8));
    }

    @Override
    protected int liveCount(Page p) { return usedOf(p.data()); }

    @Override
    protected int computeFree(Page p) {
        return (capacity - usedOf(p.data())) * recordSize;
    }

    @Override
    public synchronized Rid insert(Object[] row) {
        byte[] rec = codec.encode(row);
        if (rec.length != recordSize)
            throw new MiniDbException(MiniDbException.Code.RECORD,
                    "编码长度 " + rec.length + " != 定长 " + recordSize);
        int pid = choosePage(recordSize);
        if (pid < 0) {
            pid = newPage();
            setFree(pid, capacity * recordSize);
        }
        Page p = pool.getPage(pid);
        try {
            byte[] d = p.data();
            int slot = -1;
            for (int i = 0; i < capacity; i++)
                if (!bitGet(d, i)) { slot = i; break; }
            if (slot < 0)
                throw new MiniDbException(MiniDbException.Code.RECORD, "选页状态不一致：页已满 " + pid);
            bitSet(d, slot, true);
            short hi = TablePageHeader.numSlots(d);
            if (slot >= hi) TablePageHeader.numSlots(d, (short) (slot + 1));
            System.arraycopy(rec, 0, d, TablePageHeader.HDR_SIZE + slot * recordSize, recordSize);
            p.setDirty(true);
            setFree(pid, computeFree(p));
            rowCount++;
            return new Rid(pid, slot);
        } finally {
            pool.unpin(pid, true);
        }
    }

    private void requireLive(Page p, Rid rid) {
        if (rid.slot() >= capacity || !bitGet(p.data(), rid.slot()))
            throw new MiniDbException(MiniDbException.Code.RECORD, "记录不存在: " + rid);
    }

    @Override
    public synchronized void delete(Rid rid) {
        checkLiveRid(rid);
        boolean pageEmpty;
        Page p = pool.getPage(rid.pageId());
        try {
            requireLive(p, rid);
            bitSet(p.data(), rid.slot(), false);
            p.setDirty(true);
            setFree(rid.pageId(), computeFree(p));
            rowCount--;
            pageEmpty = usedOf(p.data()) == 0;
        } finally {
            pool.unpin(rid.pageId(), true);
        }
        if (pageEmpty && pageCount() > 1) unlinkAndFree(rid.pageId());
    }

    @Override
    public synchronized Rid update(Rid rid, Object[] newRow) {
        checkLiveRid(rid);
        byte[] rec = codec.encode(newRow);
        Page p = pool.getPage(rid.pageId());
        try {
            requireLive(p, rid);
            System.arraycopy(rec, 0, p.data(), TablePageHeader.HDR_SIZE + rid.slot() * recordSize, recordSize);
            p.setDirty(true);
        } finally {
            pool.unpin(rid.pageId(), true);
        }
        return rid; // 定长更新原地完成，RID 不变
    }

    @Override
    public synchronized Object[] get(Rid rid) {
        checkLiveRid(rid);
        Page p = pool.getPage(rid.pageId());
        try {
            requireLive(p, rid);
            return codec.decode(p.data(), TablePageHeader.HDR_SIZE + rid.slot() * recordSize, recordSize);
        } finally {
            pool.unpin(rid.pageId(), false);
        }
    }

    @Override
    protected Row rowAt(Page p, int slot) {
        if (slot >= capacity || !bitGet(p.data(), slot)) return null;
        Rid rid = new Rid(p.pageId(), slot);
        return new Row(rid, codec.decode(p.data(), TablePageHeader.HDR_SIZE + slot * recordSize, recordSize));
    }

    int capacity() { return capacity; }
}
