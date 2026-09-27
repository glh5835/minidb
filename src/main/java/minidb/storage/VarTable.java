package minidb.storage;

import minidb.common.Bytes;
import minidb.common.MiniDbException;
import minidb.common.Rid;

/**
 * 变长记录表（槽位数组 slotted page）：
 * <pre>
 *  [32B 头][槽位数组（4B/槽，向上增长）]......[记录数据（向下增长）]
 *  槽 = [offset short][len short]；len==0 表示空槽，空槽用 offset 串成空闲链。
 *  totalFree = dataStart - freeLow + Σ空槽原长度（空洞）。放不下且总量够时先压缩（槽号不变）。
 * </pre>
 * UPDATE 若新记录放不进原页则移动到其他页，返回新 RID。
 */
public final class VarTable extends BaseTable {

    public VarTable(StorageEngine engine, Schema schema, int firstPage) {
        super(engine, schema, firstPage);
        if (schema.fixedRecordSize() >= 0)
            throw new MiniDbException(MiniDbException.Code.SCHEMA,
                    "无 VARCHAR 列的表请用定长表: " + schema.tableName());
        rebuild();
    }

    @Override
    protected boolean fixedLayout() { return false; }

    @Override
    protected void initPageData(byte[] d) {
        TablePageHeader.init(d, Page.Type.VAR_TABLE);
        TablePageHeader.freeLow(d, (short) TablePageHeader.HDR_SIZE);
        TablePageHeader.dataStart(d, (short) Page.SIZE);
        TablePageHeader.totalFree(d, Page.SIZE - TablePageHeader.HDR_SIZE);
    }

    @Override
    protected int liveCount(Page p) {
        short n = TablePageHeader.numSlots(p.data());
        int live = 0;
        for (int i = 0; i < n; i++)
            if (slotLen(p.data(), i) > 0) live++;
        return live;
    }

    @Override
    protected int computeFree(Page p) {
        return TablePageHeader.totalFree(p.data());
    }

    private static int slotOff(int slot) {
        return TablePageHeader.HDR_SIZE + slot * 4;
    }

    private static short slotLen(byte[] d, int slot) {
        return Bytes.getShort(d, slotOff(slot) + 2);
    }

    private static short slotOffset(byte[] d, int slot) {
        return Bytes.getShort(d, slotOff(slot));
    }

    private static void writeSlot(byte[] d, int slot, short offset, short len) {
        Bytes.putShort(d, slotOff(slot), offset);
        Bytes.putShort(d, slotOff(slot) + 2, len);
    }

    private void validateSlot(Page p, Rid rid) {
        checkLiveRid(rid);
        byte[] d = p.data();
        if (rid.pageId() != p.pageId())
            throw new MiniDbException(MiniDbException.Code.RECORD, "RID 与页不符");
        if (rid.slot() >= TablePageHeader.numSlots(d) || slotLen(d, rid.slot()) == 0)
            throw new MiniDbException(MiniDbException.Code.RECORD, "记录不存在: " + rid);
    }

    /** 页内压缩：把存活记录先复制到临时缓冲，再紧凑搬到页尾；槽号保持不变。 */
    private void compact(Page p) {
        byte[] d = p.data();
        short n = TablePageHeader.numSlots(d);
        byte[][] recs = new byte[n][];
        for (int i = 0; i < n; i++) {
            short len = slotLen(d, i);
            if (len <= 0) continue;
            byte[] r = new byte[len];
            System.arraycopy(d, slotOffset(d, i), r, 0, len);
            recs[i] = r;
        }
        int write = Page.SIZE;
        for (int i = n - 1; i >= 0; i--) {
            if (recs[i] == null) continue;
            write -= recs[i].length;
            System.arraycopy(recs[i], 0, d, write, recs[i].length);
            writeSlot(d, i, (short) write, (short) recs[i].length);
        }
        TablePageHeader.dataStart(d, (short) write);
        TablePageHeader.totalFree(d, write - TablePageHeader.freeLow(d));
        p.setDirty(true);
    }

    @Override
    public synchronized Rid insert(Object[] row) {
        byte[] rec = codec.encode(row);
        int need = rec.length + 4;
        int pid = choosePage(need);
        if (pid < 0) {
            pid = newPage();
            setFree(pid, Page.SIZE - TablePageHeader.HDR_SIZE);
        }
        return insertIntoPage(pid, rec);
    }

    /** 在指定页放置记录（必要时压缩），分配/复用一个槽，返回 RID。 */
    private Rid insertIntoPage(int pid, byte[] rec) {
        Page p = pool.getPage(pid);
        try {
            byte[] d = p.data();
            int len = rec.length;
            int contig = TablePageHeader.dataStart(d) - TablePageHeader.freeLow(d);
            int slotNeed = TablePageHeader.firstFree(d) >= 0 ? 0 : 4;
            if (contig < len + slotNeed) {
                compact(p);
                d = p.data();
                contig = TablePageHeader.dataStart(d) - TablePageHeader.freeLow(d);
            }
            if (contig < len + slotNeed)
                throw new MiniDbException(MiniDbException.Code.RECORD,
                        "页 " + pid + " 空间不足（内部错误）");
            // 放记录
            short newStart = (short) (TablePageHeader.dataStart(d) - len);
            System.arraycopy(rec, 0, d, newStart, len);
            TablePageHeader.dataStart(d, newStart);
            // 分配槽
            int slot;
            boolean appendedSlot;
            short ff = TablePageHeader.firstFree(d);
            if (ff >= 0) {
                slot = ff;
                short nextFree = slotOffset(d, slot);
                writeSlot(d, slot, newStart, (short) len);
                TablePageHeader.firstFree(d, nextFree);
                appendedSlot = false;
            } else {
                slot = TablePageHeader.numSlots(d);
                writeSlot(d, slot, newStart, (short) len);
                TablePageHeader.numSlots(d, (short) (slot + 1));
                TablePageHeader.freeLow(d, (short) (TablePageHeader.freeLow(d) + 4));
                appendedSlot = true;
            }
            // totalFree = dataStart - freeLow + 空洞；追加槽占 4 字节，放记录占 len 字节
            TablePageHeader.totalFree(d, TablePageHeader.totalFree(d) - len - (appendedSlot ? 4 : 0));
            p.setDirty(true);
            setFree(pid, TablePageHeader.totalFree(d));
            rowCount++;
            return new Rid(pid, slot);
        } finally {
            pool.unpin(pid, true);
        }
    }

    /** 页内删除：槽标记为空并串入空闲链，更新 totalFree / freeMap / rowCount。不回收页。 */
    private void deleteInPage(Page p, Rid rid) {
        validateSlot(p, rid);
        byte[] d = p.data();
        short len = slotLen(d, rid.slot());
        writeSlot(d, rid.slot(), TablePageHeader.firstFree(d), (short) 0);
        TablePageHeader.firstFree(d, (short) rid.slot());
        TablePageHeader.totalFree(d, TablePageHeader.totalFree(d) + len);
        p.setDirty(true);
        setFree(rid.pageId(), TablePageHeader.totalFree(d));
        rowCount--;
    }

    @Override
    public synchronized void delete(Rid rid) {
        checkLiveRid(rid);
        boolean pageEmpty;
        Page p = pool.getPage(rid.pageId());
        try {
            deleteInPage(p, rid);
            // 无存活字节 ⟺ totalFree 达到上限（dataStart 不因删除回退，不能用它判空）
            byte[] d = p.data();
            pageEmpty = TablePageHeader.totalFree(d) == Page.SIZE - TablePageHeader.freeLow(d);
        } finally {
            pool.unpin(rid.pageId(), true);
        }
        if (pageEmpty && pageCount() > 1) unlinkAndFree(rid.pageId());
    }

    @Override
    public synchronized Rid update(Rid rid, Object[] newRow) {
        checkLiveRid(rid);
        byte[] rec = codec.encode(newRow);
        int contigAvail;
        Page p = pool.getPage(rid.pageId());
        try {
            validateSlot(p, rid);
            byte[] d = p.data();
            short oldLen = slotLen(d, rid.slot());
            contigAvail = TablePageHeader.dataStart(d) - TablePageHeader.freeLow(d) + oldLen;
        } finally {
            pool.unpin(rid.pageId(), false);
        }
        if (rec.length <= contigAvail) {
            // 原页更新：删旧（槽进空闲链且位于链头）→ 压缩 → 重放复用同一槽，RID 不变
            Page p2 = pool.getPage(rid.pageId());
            try {
                deleteInPage(p2, rid);
                compact(p2);
            } finally {
                pool.unpin(rid.pageId(), true);
            }
            return insertIntoPage(rid.pageId(), rec);
        }
        // 移动到其他页：删旧后 best-fit 选页（必要时开新页），RID 变化
        delete(rid);
        int pid = choosePage(rec.length + 4);
        if (pid < 0) {
            pid = newPage();
            setFree(pid, Page.SIZE - TablePageHeader.HDR_SIZE);
        }
        return insertIntoPage(pid, rec);
    }

    @Override
    public synchronized Object[] get(Rid rid) {
        checkLiveRid(rid);
        Page p = pool.getPage(rid.pageId());
        try {
            validateSlot(p, rid);
            byte[] d = p.data();
            int off = slotOffset(d, rid.slot());
            int len = slotLen(d, rid.slot());
            return codec.decode(d, off, len);
        } finally {
            pool.unpin(rid.pageId(), false);
        }
    }

    @Override
    protected Row rowAt(Page p, int slot) {
        byte[] d = p.data();
        if (slot >= TablePageHeader.numSlots(d)) return null;
        short len = slotLen(d, slot);
        if (len <= 0) return null;
        Rid rid = new Rid(p.pageId(), slot);
        return new Row(rid, codec.decode(d, slotOffset(d, slot), len));
    }
}
