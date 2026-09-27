package minidb.storage;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** BufferPool：LRU + 脏页 + 引用计数，含故障注入 */
class BufferPoolTest {
    @TempDir
    Path dir;

    private DiskManager dm(Path f) {
        return new DiskManager(f, true);
    }

    @Test
    void getPageThenUnpin() {
        try (DiskManager d = dm(dir.resolve("a.db")); BufferPool bp = new BufferPool(d, 4)) {
            Page p = bp.getPage(0);
            assertEquals(1, p.pinCount());
            bp.unpin(0, false);
            assertEquals(0, p.pinCount());
        }
    }

    @Test
    void samePageReturnsSameInstanceWhilePinned() {
        try (DiskManager d = dm(dir.resolve("b.db")); BufferPool bp = new BufferPool(d, 4)) {
            Page p1 = bp.getPage(7);
            Page p2 = bp.getPage(7);
            assertSame(p1, p2);
            assertEquals(2, p1.pinCount());
        }
    }

    @Test
    void hitVsMissCounting() {
        try (DiskManager d = dm(dir.resolve("c.db")); BufferPool bp = new BufferPool(d, 4)) {
            bp.getPage(0);
            bp.unpin(0, false);
            bp.getPage(0); // 命中
            bp.unpin(0, false);
            bp.getPage(1); // 未命中
            bp.unpin(1, false);
            assertEquals(1, bp.hits());
            assertEquals(2, bp.misses());
        }
    }

    @Test
    void lruEvictsLeastRecentlyUsed() {
        try (DiskManager d = dm(dir.resolve("d.db")); BufferPool bp = new BufferPool(d, 2)) {
            bp.getPage(1);
            bp.unpin(1, false);
            bp.getPage(2);
            bp.unpin(2, false);
            bp.getPage(1); // 1 变为最近使用
            bp.unpin(1, false);
            bp.getPage(3); // 应淘汰 2
            bp.unpin(3, false);
            assertEquals(2, bp.size());
            assertEquals(1, bp.evictions());
            // 页 2 已被淘汰：再次访问应记 miss（misses 累计：1、2 各一次 + 3 一次 + 2 再一次）
            bp.getPage(2);
            assertEquals(4, bp.misses());
            bp.unpin(2, false);
        }
    }

    @Test
    void pinnedPageIsNotEvicted() {
        try (DiskManager d = dm(dir.resolve("e.db")); BufferPool bp = new BufferPool(d, 2)) {
            bp.getPage(1); // 保持 pin
            bp.getPage(2);
            bp.unpin(2, false);
            bp.getPage(3); // 只能淘汰 2，不能动 1
            bp.unpin(3, false);
            assertEquals(1, bp.evictions());
        }
    }

    @Test
    void allPinnedThrowsBufferFull() {
        try (DiskManager d = dm(dir.resolve("f.db")); BufferPool bp = new BufferPool(d, 2)) {
            bp.getPage(1);
            bp.getPage(2);
            assertThrows(MiniDbException.class, () -> bp.getPage(3));
            assertEquals(MiniDbException.Code.BUFFER_FULL,
                    assertThrows(MiniDbException.class, () -> bp.getPage(4)).code);
        }
    }

    @Test
    void unpinReenablesEviction() {
        try (DiskManager d = dm(dir.resolve("g.db")); BufferPool bp = new BufferPool(d, 1)) {
            bp.getPage(1);
            assertThrows(MiniDbException.class, () -> bp.getPage(2));
            bp.unpin(1, false);
            bp.getPage(2); // 现在可以淘汰 1
            bp.unpin(2, false);
            assertEquals(1, bp.size());
        }
    }

    @Test
    void doubleUnpinThrows() {
        try (DiskManager d = dm(dir.resolve("h.db")); BufferPool d2 = new BufferPool(d, 2)) {
            d2.getPage(0);
            d2.unpin(0, false);
            assertThrows(MiniDbException.class, () -> d2.unpin(0, false));
        }
    }

    @Test
    void unpinForeignPageThrows() {
        try (DiskManager d = dm(dir.resolve("i.db")); BufferPool bp = new BufferPool(d, 2)) {
            assertThrows(MiniDbException.class, () -> bp.unpin(99, false));
        }
    }

    @Test
    void dirtyPageWrittenBackOnEvict() {
        Path f = dir.resolve("j.db");
        try (DiskManager d = dm(f); BufferPool bp = new BufferPool(d, 1)) {
            Page p = bp.getPage(0);
            p.data()[5] = 55;
            bp.unpin(0, true);
            bp.getPage(1); // 触发淘汰 → 写回页 0
            bp.unpin(1, false);
            assertEquals(1, bp.writebacks());
        }
        try (DiskManager d = dm(f); BufferPool bp = new BufferPool(d, 1)) {
            Page p = bp.getPage(0);
            assertEquals(55, p.data()[5]);
            bp.unpin(0, false);
        }
    }

    @Test
    void cleanPageNotWrittenBackOnEvict() {
        try (DiskManager d = dm(dir.resolve("k.db")); BufferPool bp = new BufferPool(d, 1)) {
            bp.getPage(0);
            bp.unpin(0, false); // 未标脏
            bp.getPage(1);
            bp.unpin(1, false);
            assertEquals(0, bp.writebacks());
        }
    }

    @Test
    void flushAllPersistsDirtyPages() {
        Path f = dir.resolve("l.db");
        try (DiskManager d = dm(f); BufferPool bp = new BufferPool(d, 4)) {
            Page p = bp.getPage(2);
            p.data()[0] = 12;
            bp.unpin(2, true);
            bp.flushAll();
            assertEquals(1, bp.writebacks());
            assertFalse(p.isDirty());
        }
        try (DiskManager d = dm(f); BufferPool bp = new BufferPool(d, 4)) {
            assertEquals(12, bp.getPage(2).data()[0]);
            bp.unpin(2, false);
        }
    }

    @Test
    void flushPageSkipsClean() {
        try (DiskManager d = dm(dir.resolve("m.db")); BufferPool bp = new BufferPool(d, 4)) {
            bp.getPage(0);
            bp.unpin(0, false);
            bp.flushPage(0);
            assertEquals(0, bp.writebacks());
        }
    }

    @Test
    void discardPageRemovesWithoutWriteback() {
        try (DiskManager d = dm(dir.resolve("n.db")); BufferPool bp = new BufferPool(d, 4)) {
            Page p = bp.getPage(0);
            p.data()[0] = 1;
            bp.unpin(0, true);
            bp.discardPage(0); // 脏页直接丢弃不写回
            assertEquals(0, bp.writebacks());
            assertEquals(0, bp.size());
        }
    }

    @Test
    void discardPinnedPageThrows() {
        try (DiskManager d = dm(dir.resolve("o.db")); BufferPool bp = new BufferPool(d, 4)) {
            bp.getPage(0);
            assertThrows(MiniDbException.class, () -> bp.discardPage(0));
            bp.unpin(0, false);
        }
    }

    @Test
    void zeroCapacityRejected() {
        try (DiskManager d = dm(dir.resolve("p.db"))) {
            assertThrows(MiniDbException.class, () -> new BufferPool(d, 0));
        }
    }

    @Test
    void flushHookCalledBeforeWriteback() {
        try (DiskManager d = dm(dir.resolve("q.db")); BufferPool bp = new BufferPool(d, 1)) {
            int[] hooked = {0};
            bp.setFlushHook(page -> hooked[0] = page.pageId());
            Page p = bp.getPage(3);
            p.data()[0] = 1;
            bp.unpin(3, true);
            bp.getPage(4); // 淘汰 3 → 先走钩子再写盘
            bp.unpin(4, false);
            assertEquals(3, hooked[0]);
        }
    }
}
