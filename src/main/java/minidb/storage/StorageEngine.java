package minidb.storage;

import minidb.common.Bytes;
import minidb.common.MiniDbException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储引擎：自管理页分配与回收（空闲空间位图）。
 *
 * 文件布局：
 *  - 页 0：元数据页（magic、版本、pageCount、位图页链表头）
 *  - 页 1：目录页（Catalog）
 *  - 位图页：页头 4 字节存 next 指针，偏移 4 起共 4092 字节位图，覆盖 4092*8 = 32736 个页号；
 *    第 k 张位图页覆盖页号 [k*32736, (k+1)*32736)，按链表顺序编号。
 *
 * 分配策略：位图 first-fit，优先复用已回收的页；所有位图都满时在文件尾部追加一张
 * 位图页，新位图页自己的页号恰好落在自己覆盖的区间内（自举，顺手给自己置位）。
 */
public final class StorageEngine implements AutoCloseable {
    static final int MAGIC = 0x4D696E44; // "MinD"
    static final int VERSION = 1;
    static final int META_PAGE = 0;
    static final int CATALOG_PAGE = 1;
    static final int BITMAP_BODY_OFF = 4;
    static final int PAGES_PER_BITMAP = (Page.SIZE - BITMAP_BODY_OFF) * 8; // 32736

    private final DiskManager disk;
    private final BufferPool pool;
    private int pageCount;      // 分配出去的最大页号 + 1（高水位）
    private int firstBitmap;    // 位图页链表头，-1 表示还没有
    private int bitmapCount;
    /** 系统脏页（元数据/位图）：提交时随事务页一起刷盘 */
    private final java.util.Set<Integer> systemDirty = new java.util.LinkedHashSet<>();

    public StorageEngine(Path file, int bufferPages) {
        boolean isNew = !Files.exists(file);
        DiskManager d = null;
        try {
            d = new DiskManager(file, true);
            this.disk = d;
            this.pool = new BufferPool(d, bufferPages);
            if (isNew || d.filePages() == 0) initMeta();
            else loadMeta();
        } catch (RuntimeException e) {
            if (d != null) d.close(); // 别泄漏文件句柄
            throw e;
        }
    }

    public BufferPool pool() { return pool; }
    public DiskManager disk() { return disk; }
    public int pageCount() { return pageCount; }
    public int bitmapCount() { return bitmapCount; }

    private void initMeta() {
        pageCount = CATALOG_PAGE + 1; // 0=meta 1=catalog
        firstBitmap = -1;
        bitmapCount = 0;
        saveMeta();
    }

    private void loadMeta() {
        Page p = pool.getPage(META_PAGE);
        try {
            byte[] d = p.data();
            if (Bytes.getInt(d, 0) != MAGIC)
                throw new MiniDbException(MiniDbException.Code.IO,
                        "不是 MiniDB 数据库文件（magic 不匹配）: " + disk.file());
            int version = Bytes.getInt(d, 4);
            if (version != VERSION)
                throw new MiniDbException(MiniDbException.Code.IO, "不支持的文件版本: " + version);
            pageCount = Bytes.getInt(d, 8);
            firstBitmap = Bytes.getInt(d, 12);
            bitmapCount = Bytes.getInt(d, 16);
        } finally {
            pool.unpin(META_PAGE, false);
        }
    }

    private void saveMeta() {
        Page p = pool.getPage(META_PAGE);
        byte[] d = p.data();
        Bytes.putInt(d, 0, MAGIC);
        Bytes.putInt(d, 4, VERSION);
        Bytes.putInt(d, 8, pageCount);
        Bytes.putInt(d, 12, firstBitmap);
        Bytes.putInt(d, 16, bitmapCount);
        p.setDirty(true);
        pool.unpin(META_PAGE, true);
        systemDirty.add(META_PAGE);
    }

    /** 分配一个新页（位图置位），返回页号。位图页是内部页，不会作为结果返回。 */
    public synchronized int allocPage() {
        int id = scanFreeBit();
        if (id < 0) {
            appendBitmapPage();   // 内部页：位图自举
            id = scanFreeBit();   // 新位图覆盖区间有空位，重扫
            if (id < 0)
                throw new MiniDbException(MiniDbException.Code.IO, "位图追加后仍无空闲页");
        }
        markBit(id, true);
        if (id + 1 > pageCount) pageCount = id + 1;
        saveMeta();
        return id;
    }

    /** 在位图链里找第一个 0 位（优先复用被回收的页）；找不到返回 -1。 */
    private int scanFreeBit() {
        int cur = firstBitmap, k = 0;
        while (cur >= 0) {
            Page bp = pool.getPage(cur);
            int found = -1, next;
            try {
                byte[] d = bp.data();
                for (int off = BITMAP_BODY_OFF; off < Page.SIZE && found < 0; off++) {
                    int b = d[off] & 0xFF;
                    if (b != 0xFF) {
                        for (int bit = 0; bit < 8; bit++) {
                            if ((b & (1 << bit)) == 0) {
                                found = k * PAGES_PER_BITMAP + (off - BITMAP_BODY_OFF) * 8 + bit;
                                break;
                            }
                        }
                    }
                }
                next = Bytes.getInt(d, 0);
            } finally {
                pool.unpin(cur, false);
            }
            if (found >= 0) return found;
            cur = next;
            k++;
        }
        return -1;
    }

    /** 追加一张位图页（内部页）：链入链表、把区间内已存在的保留页与自身标记为已用。 */
    private void appendBitmapPage() {
        // 走到这里说明现有位图已满（或尚无位图）：新位图页取文件尾部下一个空闲页号。
        // 该页号必然落在新位图自身覆盖的区间内（自举）。
        int id = pageCount;
        if (id / PAGES_PER_BITMAP != bitmapCount)
            throw new MiniDbException(MiniDbException.Code.IO,
                    "内部错误：位图自举页号越界 id=" + id + " bitmapCount=" + bitmapCount);
        Page bp = pool.getPage(id);
        try {
            java.util.Arrays.fill(bp.data(), (byte) 0);
            bp.data()[TablePageHeader.OFF_TYPE] = Page.Type.BITMAP.id;
            Bytes.putInt(bp.data(), 0, -1); // next = -1
            bp.setDirty(true);
        } finally {
            pool.unpin(id, true);
        }
        // 挂到链尾
        if (firstBitmap < 0) {
            firstBitmap = id;
        } else {
            int tail = firstBitmap;
            while (true) {
                int next = pool.withPage(tail, false, p -> Bytes.getInt(p.data(), 0));
                if (next < 0) break;
                tail = next;
            }
            Page tailPage = pool.getPage(tail);
            try {
                Bytes.putInt(tailPage.data(), 0, id);
                tailPage.setDirty(true);
            } finally {
                pool.unpin(tail, true);
            }
        }
        bitmapCount++;
        // 把本区间内已存在但尚未登记的页标记为已分配（仅首张位图时有：保留页 0、1；
        // 后续位图只在前面全部写满时追加，此时区间起点 == pageCount）
        for (int p = (id / PAGES_PER_BITMAP) * PAGES_PER_BITMAP; p <= pageCount; p++)
            setBit(p, true);
    }

    private Page bitmapPageAt(int k) {
        if (k < 0 || k >= bitmapCount)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "位图页不存在: #" + k);
        int cur = firstBitmap;
        for (int i = 0; i < k; i++) {
            cur = pool.withPage(cur, false, p -> Bytes.getInt(p.data(), 0));
        }
        return pool.getPage(cur);
    }

    private void setBit(int pageId, boolean on) {
        int k = pageId / PAGES_PER_BITMAP;
        Page bp = bitmapPageAt(k);
        try {
            int idx = pageId % PAGES_PER_BITMAP;
            int off = BITMAP_BODY_OFF + idx / 8;
            if (on) bp.data()[off] |= (byte) (1 << (idx % 8));
            else bp.data()[off] &= (byte) ~(1 << (idx % 8));
            bp.setDirty(true);
        } finally {
            pool.unpin(bp.pageId(), true);
        }
        systemDirty.add(bp.pageId());
    }

    private void markBit(int pageId, boolean on) {
        if (pageId / PAGES_PER_BITMAP >= bitmapCount && on)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID,
                    "页号超出位图覆盖: " + pageId);
        setBit(pageId, on);
    }

    /** 回收页：清位图位、从缓冲池丢弃、磁盘清零（防止复用后读到旧数据）。 */
    public synchronized void freePage(int pageId) {
        if (pageId <= CATALOG_PAGE)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "保留页不能回收: " + pageId);
        if (!isAllocated(pageId))
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "回收未分配的页: " + pageId);
        setBit(pageId, false);
        pool.discardPage(pageId);
        byte[] zeros = new byte[Page.SIZE];
        disk.writePage(pageId, zeros);
    }

    public boolean isAllocated(int pageId) {
        if (pageId < 0 || pageId >= pageCount) return false;
        int k = pageId / PAGES_PER_BITMAP;
        if (k >= bitmapCount) return false;
        Page bp = bitmapPageAt(k);
        try {
            int idx = pageId % PAGES_PER_BITMAP;
            return (bp.data()[BITMAP_BODY_OFF + idx / 8] & (1 << (idx % 8))) != 0;
        } finally {
            pool.unpin(bp.pageId(), false);
        }
    }

    /** 刷出系统页（元数据/位图）。事务提交时与事务数据页一起调用。 */
    public synchronized void flushSystemPages() {
        for (int pid : systemDirty) pool.flushPage(pid);
        systemDirty.clear();
    }

    public void flush() {
        pool.flushAll();
        disk.sync();
    }

    /** 模拟断电：跳过缓冲池刷盘直接关文件。 */
    public void abruptClose() {
        disk.close();
    }

    @Override
    public void close() {
        pool.close();
        disk.close();
    }
}
