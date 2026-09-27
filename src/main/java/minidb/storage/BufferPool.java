package minidb.storage;

import minidb.common.MiniDbException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓冲池：固定容量（页数），LRU 淘汰 + 脏页写回 + 引用计数。
 *
 * 契约：getPage 返回的页处于 pin 状态；用完必须 unpin(pageId, 是否弄脏)。
 * pin 数大于 0 的页不会被淘汰；全部页都被 pin 时再加载新页会抛 BUFFER_FULL（故障注入点）。
 * 只有在 pin 状态下才允许使用 Page 引用（unpin 后随时可能被淘汰复用）。
 */
public final class BufferPool implements AutoCloseable {
    /** 页被写盘前的钩子，阶段5 WAL 用它保证“先写日志后写数据”。 */
    public interface FlushHook {
        void beforePageFlush(Page page);
    }

    private final DiskManager disk;
    private final int capacity;
    private final LinkedHashMap<Integer, Page> pool;
    private FlushHook flushHook;

    private long hits, misses, evictions, writebacks;

    public BufferPool(DiskManager disk, int capacityPages) {
        if (capacityPages <= 0)
            throw new MiniDbException(MiniDbException.Code.BUFFER_FULL, "缓冲池容量必须 > 0");
        this.disk = disk;
        this.capacity = capacityPages;
        // accessOrder=true：get 也会把页移到队尾，从而实现 LRU
        this.pool = new LinkedHashMap<>(capacityPages * 2, 0.75f, true);
    }

    public void setFlushHook(FlushHook hook) { this.flushHook = hook; }
    public int capacity() { return capacity; }
    public synchronized int size() { return pool.size(); }
    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized long evictions() { return evictions; }
    public synchronized long writebacks() { return writebacks; }

    /** 取页并 pin。若不在池中则淘汰一个未 pin 的 LRU 页后从磁盘加载。 */
    public synchronized Page getPage(int pageId) {
        Page p = pool.get(pageId);
        if (p != null) {
            hits++;
            p.incPin();
            return p;
        }
        misses++;
        if (pool.size() >= capacity) evictOne();
        p = new Page(pageId);
        byte[] tmp = new byte[Page.SIZE];
        disk.readPage(pageId, tmp);
        p.loadFrom(tmp, Page.SIZE);
        p.incPin();
        pool.put(pageId, p);
        return p;
    }

    public synchronized void unpin(int pageId, boolean dirty) {
        Page p = pool.get(pageId);
        if (p == null)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "unpin 不在池中的页: " + pageId);
        if (dirty) p.setDirty(true);
        p.decPin();
    }

    /** 便捷用法：在回调里使用页，结束自动 unpin。 */
    public <T> T withPage(int pageId, boolean dirty, java.util.function.Function<Page, T> fn) {
        Page p = getPage(pageId);
        try {
            return fn.apply(p);
        } finally {
            unpin(pageId, dirty);
        }
    }

    private void evictOne() {
        Iterator<Map.Entry<Integer, Page>> it = pool.entrySet().iterator();
        while (it.hasNext()) {
            Page victim = it.next().getValue();
            if (victim.pinCount() > 0) continue;
            if (victim.isDirty()) {
                flushHookBefore(victim);
                disk.writePage(victim.pageId(), victim.data());
                writebacks++;
            }
            it.remove();
            evictions++;
            return;
        }
        throw new MiniDbException(MiniDbException.Code.BUFFER_FULL,
                "缓冲池已满且所有页都被 pin，无法加载新页（容量=" + capacity + "）");
    }

    /** 把指定页写盘（无论是否 dirty），不淘汰、不改 pin。 */
    public synchronized void flushPage(int pageId) {
        Page p = pool.get(pageId);
        if (p == null) return;
        if (p.isDirty()) {
            flushHookBefore(p);
            disk.writePage(pageId, p.data());
            writebacks++;
            p.setDirty(false);
        }
    }

    public synchronized void flushAll() {
        for (Page p : pool.values()) {
            if (p.isDirty()) {
                flushHookBefore(p);
                disk.writePage(p.pageId(), p.data());
                writebacks++;
                p.setDirty(false);
            }
        }
    }

    /** 页被上层释放（如页回收）后从池里丢弃，防止再写回僵尸页。 */
    public synchronized void discardPage(int pageId) {
        Page p = pool.get(pageId);
        if (p != null && p.pinCount() > 0)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID,
                    "页 " + pageId + " 仍被 pin，不能丢弃");
        pool.remove(pageId);
    }

    private void flushHookBefore(Page p) {
        if (flushHook != null) flushHook.beforePageFlush(p);
    }

    @Override
    public synchronized void close() {
        flushAll();
    }
}
