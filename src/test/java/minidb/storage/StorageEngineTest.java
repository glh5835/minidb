package minidb.storage;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** StorageEngine：页分配/回收/位图链，含故障注入 */
class StorageEngineTest {
    @TempDir
    Path dir;

    private StorageEngine se(Path f, int buf) {
        return new StorageEngine(f, buf);
    }

    @Test
    void allocSequentialIds() {
        try (StorageEngine se = se(dir.resolve("a.db"), 64)) {
            assertEquals(2, se.pageCount());
            int p1 = se.allocPage();
            int p2 = se.allocPage();
            assertEquals(3, p1);
            assertEquals(4, p2);
        }
    }

    @Test
    void freedPageReusedByNextAlloc() {
        try (StorageEngine se = se(dir.resolve("b.db"), 64)) {
            int p = se.allocPage();
            se.freePage(p);
            assertEquals(p, se.allocPage()); // first-fit 复用
            assertFalse(se.isAllocated(p + 100));
        }
    }

    @Test
    void allocationPersistsAcrossReopen() {
        Path f = dir.resolve("c.db");
        try (StorageEngine se = se(f, 64)) {
            se.allocPage(); // 3（位图自举占 2）
            se.allocPage(); // 4
        }
        try (StorageEngine se = se(f, 64)) {
            assertEquals(5, se.pageCount()); // 高水位 = 最后页号 + 1
            assertTrue(se.isAllocated(2)); // 位图页
            assertTrue(se.isAllocated(3));
            assertTrue(se.isAllocated(4));
        }
    }

    @Test
    void freePagePersistsAcrossReopen() {
        Path f = dir.resolve("d.db");
        try (StorageEngine se = se(f, 64)) {
            int p = se.allocPage();
            se.freePage(p);
        }
        try (StorageEngine se = se(f, 64)) {
            assertFalse(se.isAllocated(3)); // 位已清
            assertEquals(4, se.pageCount()); // 高水位保留
        }
    }

    @Test
    void bitmapChainSpansMultipleBitmapPages() {
        // 分配超过一张位图页的覆盖量（32736），验证链式位图（只写位图，不写数据，速度快）
        try (StorageEngine se = se(dir.resolve("e.db"), 64)) {
            int n = StorageEngine.PAGES_PER_BITMAP + 10;
            for (int i = 0; i < n; i++) se.allocPage();
            // 保留页 0,1 + 位图 2 页 + n 个数据页；高水位 = 最后数据页 + 1
            assertEquals(n + 4, se.pageCount());
            assertEquals(2, se.bitmapCount());
            assertTrue(se.isAllocated(n + 3)); // 最后分配的数据页
            assertTrue(se.isAllocated(StorageEngine.PAGES_PER_BITMAP + 1));
        }
    }

    @Test
    void bitmapChainSurvivesReopen() {
        Path f = dir.resolve("f.db");
        try (StorageEngine se = se(f, 64)) {
            for (int i = 0; i < StorageEngine.PAGES_PER_BITMAP + 5; i++) se.allocPage();
        }
        try (StorageEngine se = se(f, 64)) {
            assertEquals(2, se.bitmapCount());
            assertTrue(se.isAllocated(StorageEngine.PAGES_PER_BITMAP + 4));
            int more = se.allocPage();
            assertTrue(se.isAllocated(more));
        }
    }

    @Test
    void freeThenReallocAcrossBitmapBoundary() {
        try (StorageEngine se = se(dir.resolve("g.db"), 64)) {
            int last = 0;
            for (int i = 0; i < StorageEngine.PAGES_PER_BITMAP + 5; i++) last = se.allocPage();
            se.freePage(last);
            assertEquals(last, se.allocPage());
        }
    }

    @Test
    void freeReservedPageThrows() {
        try (StorageEngine se = se(dir.resolve("h.db"), 64)) {
            assertThrows(MiniDbException.class, () -> se.freePage(0));
            assertThrows(MiniDbException.class, () -> se.freePage(1));
        }
    }

    @Test
    void freeUnallocatedPageThrows() {
        try (StorageEngine se = se(dir.resolve("i.db"), 64)) {
            assertThrows(MiniDbException.class, () -> se.freePage(50));
            int p = se.allocPage();
            se.freePage(p);
            assertThrows(MiniDbException.class, () -> se.freePage(p)); // 二次回收
        }
    }

    @Test
    void garbageFileRejectedByMagic() throws Exception {
        Path f = dir.resolve("j.db");
        Files.write(f, new byte[Page.SIZE]);
        MiniDbException e = assertThrows(MiniDbException.class, () -> se(f, 64));
        assertEquals(MiniDbException.Code.IO, e.code);
    }

    @Test
    void freedPageZeroedOnDisk() {
        Path f = dir.resolve("k.db");
        try (StorageEngine se = se(f, 64)) {
            int p = se.allocPage();
            se.pool().getPage(p).data()[100] = 77;
            se.pool().unpin(p, true);
            se.freePage(p);
        }
        try (DiskManager dm = new DiskManager(f, false)) {
            byte[] buf = new byte[Page.SIZE];
            dm.readPage(2, buf);
            assertEquals(0, buf[100]);
        }
    }

    @Test
    void isAllocatedBounds() {
        try (StorageEngine se = se(dir.resolve("l.db"), 64)) {
            assertFalse(se.isAllocated(-1));
            assertFalse(se.isAllocated(0)); // 保留页未在位图中登记
            assertFalse(se.isAllocated(1_000_000));
            int p = se.allocPage();
            assertTrue(se.isAllocated(p));
        }
    }
}
