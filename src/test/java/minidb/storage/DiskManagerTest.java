package minidb.storage;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** DiskManager：页粒度文件 IO + 边界与故障注入 */
class DiskManagerTest {
    @TempDir
    Path dir;

    @Test
    void createNewFile() {
        Path f = dir.resolve("a.db");
        try (DiskManager dm = new DiskManager(f, true)) {
            assertEquals(0, dm.filePages());
        }
        assertTrue(java.nio.file.Files.exists(f));
    }

    @Test
    void missingFileWithoutCreateThrows() {
        assertThrows(MiniDbException.class, () -> new DiskManager(dir.resolve("nope.db"), false));
    }

    @Test
    void writeThenReadPage() {
        try (DiskManager dm = new DiskManager(dir.resolve("b.db"), true)) {
            byte[] w = new byte[Page.SIZE];
            w[0] = 42;
            w[Page.SIZE - 1] = 7;
            dm.writePage(3, w);
            byte[] r = new byte[Page.SIZE];
            dm.readPage(3, r);
            assertEquals(42, r[0]);
            assertEquals(7, r[Page.SIZE - 1]);
            assertEquals(4, dm.filePages());
        }
    }

    @Test
    void readBeyondFileZeroFills() {
        try (DiskManager dm = new DiskManager(dir.resolve("c.db"), true)) {
            byte[] r = new byte[Page.SIZE];
            dm.readPage(100, r); // 文件只有 0 页，读出全零（新页语义）
            for (byte b : r) assertEquals(0, b);
        }
    }

    @Test
    void negativePageIdThrows() {
        try (DiskManager dm = new DiskManager(dir.resolve("d.db"), true)) {
            byte[] b = new byte[Page.SIZE];
            assertThrows(MiniDbException.class, () -> dm.readPage(-1, b));
            assertThrows(MiniDbException.class, () -> dm.writePage(-5, b));
        }
    }

    @Test
    void smallBufferThrows() {
        try (DiskManager dm = new DiskManager(dir.resolve("e.db"), true)) {
            byte[] b = new byte[Page.SIZE - 1];
            assertThrows(MiniDbException.class, () -> dm.writePage(0, b));
        }
    }

    @Test
    void dataPersistsAcrossReopen() {
        Path f = dir.resolve("f.db");
        byte[] w = new byte[Page.SIZE];
        w[10] = 99;
        try (DiskManager dm = new DiskManager(f, true)) {
            dm.writePage(2, w);
        }
        try (DiskManager dm = new DiskManager(f, false)) {
            byte[] r = new byte[Page.SIZE];
            dm.readPage(2, r);
            assertEquals(99, r[10]);
        }
    }

    @Test
    void pageCountGrowsWithWrites() {
        try (DiskManager dm = new DiskManager(dir.resolve("g.db"), true)) {
            assertEquals(0, dm.filePages());
            dm.writePage(9, new byte[Page.SIZE]);
            assertEquals(10, dm.filePages());
            dm.writePage(4, new byte[Page.SIZE]);
            assertEquals(10, dm.filePages()); // 向中间写不改变长度
        }
    }

    @Test
    void readsAndWritesCounted() {
        try (DiskManager dm = new DiskManager(dir.resolve("h.db"), true)) {
            dm.writePage(0, new byte[Page.SIZE]);
            dm.readPage(0, new byte[Page.SIZE]);
            assertEquals(1, dm.writes());
            assertEquals(1, dm.reads());
        }
    }
}
