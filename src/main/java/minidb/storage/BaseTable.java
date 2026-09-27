package minidb.storage;

import minidb.common.MiniDbException;
import minidb.common.Rid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 表实现的公共骨架：页链管理 + 基于空闲字节数的 best-fit 选页。
 * 子类负责页内布局（位图定长槽 / 槽位数组+变长记录）。
 */
abstract class BaseTable implements Table {
    protected final StorageEngine engine;
    protected final BufferPool pool;
    protected final Schema schema;
    protected final RowCodec codec;

    protected int firstPage; // 0 表示无页
    protected int lastPage;
    protected int rowCount;

    /** 页号 -> 空闲字节数；freeBySpace 反向索引按空闲字节 best-fit 选页。 */
    protected final Map<Integer, Integer> pageFree = new HashMap<>();
    protected final TreeMap<Integer, TreeSet<Integer>> freeBySpace = new TreeMap<>();

    BaseTable(StorageEngine engine, Schema schema, int firstPage) {
        this.engine = engine;
        this.pool = engine.pool();
        this.schema = schema;
        this.codec = new RowCodec(schema);
        this.firstPage = firstPage;
        // 注意：rebuild() 由子类构造器在自身字段就绪后调用
    }

    protected final void rebuild() {
        rowCount = 0;
        pageFree.clear();
        freeBySpace.clear();
        lastPage = 0;
        int cur = firstPage, prev = 0;
        while (cur != 0) {
            Page p = pool.getPage(cur);
            int next;
            try {
                Page.Type expect = fixedLayout() ? Page.Type.FIXED_TABLE : Page.Type.VAR_TABLE;
                if (p.type() != expect)
                    throw new MiniDbException(MiniDbException.Code.PAGE_INVALID,
                            "页 " + cur + " 类型错误: " + p.type() + " 期望 " + expect);
                next = TablePageHeader.nextPage(p.data());
                rowCount += liveCount(p);
                setFree(cur, computeFree(p));
            } finally {
                pool.unpin(cur, false);
            }
            prev = cur;
            cur = next;
        }
        lastPage = prev;
    }

    protected abstract boolean fixedLayout();

    /** 页内存活记录数（打开时重建 rowCount 用） */
    protected abstract int liveCount(Page p);

    /** 页当前空闲字节数 */
    protected abstract int computeFree(Page p);

    protected abstract void initPageData(byte[] d);

    protected final void setFree(int pid, int freeBytes) {
        Integer old = pageFree.remove(pid);
        if (old != null) {
            TreeSet<Integer> s = freeBySpace.get(old);
            if (s != null) {
                s.remove(pid);
                if (s.isEmpty()) freeBySpace.remove(old);
            }
        }
        pageFree.put(pid, freeBytes);
        freeBySpace.computeIfAbsent(freeBytes, k -> new TreeSet<>()).add(pid);
    }

    /** best-fit：空闲字节数最小但 >= need 的页；没有返回 -1。 */
    protected final int choosePage(int need) {
        var e = freeBySpace.ceilingEntry(need);
        return e == null ? -1 : e.getValue().first();
    }

    /** 分配并链接一个新页，返回页号。 */
    protected final int newPage() {
        int pid = engine.allocPage();
        Page p = pool.getPage(pid);
        try {
            initPageData(p.data());
            TablePageHeader.prevPage(p.data(), lastPage);
            TablePageHeader.nextPage(p.data(), 0);
            p.setDirty(true);
        } finally {
            pool.unpin(pid, true);
        }
        if (lastPage != 0) {
            Page prev = pool.getPage(lastPage);
            try {
                TablePageHeader.nextPage(prev.data(), pid);
                prev.setDirty(true);
            } finally {
                pool.unpin(lastPage, true);
            }
        }
        if (firstPage == 0) firstPage = pid;
        lastPage = pid;
        setFree(pid, 0); // 稍后插入/初始化时会更新
        return pid;
    }

    protected final void unlinkAndFree(int pid) {
        int[] nb = pool.withPage(pid, false, p -> new int[]{
                TablePageHeader.nextPage(p.data()), TablePageHeader.prevPage(p.data())});
        int next = nb[0], prev = nb[1];
        if (prev != 0) {
            Page pp = pool.getPage(prev);
            try {
                TablePageHeader.nextPage(pp.data(), next);
                pp.setDirty(true);
            } finally {
                pool.unpin(prev, true);
            }
        }
        if (next != 0) {
            Page np = pool.getPage(next);
            try {
                TablePageHeader.prevPage(np.data(), prev);
                np.setDirty(true);
            } finally {
                pool.unpin(next, true);
            }
        }
        if (pid == firstPage) firstPage = next;
        if (pid == lastPage) lastPage = prev;
        Integer free = pageFree.remove(pid);
        if (free != null) {
            TreeSet<Integer> s = freeBySpace.get(free);
            if (s != null) {
                s.remove(pid);
                if (s.isEmpty()) freeBySpace.remove(free);
            }
        }
        engine.freePage(pid);
    }

    protected final void checkLiveRid(Rid rid) {
        if (rid == null || rid.pageId() <= 0 || rid.slot() < 0)
            throw new MiniDbException(MiniDbException.Code.RECORD, "非法 RID: " + rid);
    }

    @Override
    public Schema schema() { return schema; }

    @Override
    public int rowCount() { return rowCount; }

    @Override
    public int pageCount() { return pageFree.size(); }

    @Override
    public long totalFreeBytes() {
        long n = 0;
        for (int f : pageFree.values()) n += f;
        return n;
    }

    @Override
    public Iterator<Row> scan() {
        return new Iterator<>() {
            private int curPage = firstPage;
            private int curSlot = 0;
            private Row pending;

            private void findNext() {
                while (pending == null && curPage != 0) {
                    int nextPage;
                    Page p = pool.getPage(curPage);
                    try {
                        nextPage = TablePageHeader.nextPage(p.data());
                        int n = Short.toUnsignedInt(TablePageHeader.numSlots(p.data()));
                        while (curSlot < n) {
                            Row r = rowAt(p, curSlot++);
                            if (r != null) {
                                pending = r;
                                break;
                            }
                        }
                    } finally {
                        pool.unpin(curPage, false);
                    }
                    if (pending == null) {
                        curPage = nextPage;
                        curSlot = 0;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                findNext();
                return pending != null;
            }

            @Override
            public Row next() {
                findNext();
                if (pending == null)
                    throw new java.util.NoSuchElementException();
                Row r = pending;
                pending = null;
                return r;
            }
        };
    }

    /** 读取页内槽 slot 的行；空槽返回 null。 */
    protected abstract Row rowAt(Page p, int slot);

    /** 调试/测试辅助：收集整表 RID（按逻辑顺序）。 */
    final List<Rid> allRids() {
        List<Rid> out = new ArrayList<>();
        for (Iterator<Row> it = scan(); it.hasNext(); ) out.add(it.next().rid());
        return out;
    }
}
