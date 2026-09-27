package minidb.wal;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** WAL 日志格式与边界：记录往返、镜像约束、残缺尾部自愈、checkpoint 截断 */
class WalLogTest {
    @TempDir
    Path dir;

    private Path walFile() {
        return dir.resolve("t.db.wal");
    }

    @Test
    void appendReadBackRoundTrip() throws Exception {
        Path f = walFile();
        try (WalLog wal = new WalLog(f)) {
            assertEquals(1, wal.append(WalLog.BEGIN, 7, "t", 0, 0, null, null));
            assertEquals(2, wal.append(WalLog.INSERT, 7, "emp", 3, 1, null, "row-after".getBytes()));
            assertEquals(3, wal.append(WalLog.UPDATE, 7, "emp", 3, 1, "row-before".getBytes(), "row-after2".getBytes()));
            assertEquals(4, wal.append(WalLog.DELETE, 7, "emp", 3, 1, "row-before".getBytes(), null));
            assertEquals(5, wal.append(WalLog.COMMIT, 7, "t", 0, 0, null, null));
        }
        try (WalLog wal = new WalLog(f)) {
            List<WalLog.Rec> rs = wal.readAll();
            assertEquals(5, rs.size());
            long prev = 0;
            for (WalLog.Rec r : rs) {
                assertEquals(r.lsn(), prev + 1, "LSN 连续单调");
                assertEquals(7, r.txnId());
                prev = r.lsn();
            }
            WalLog.Rec begin = rs.get(0);
            assertEquals(WalLog.BEGIN, begin.type());
            assertNull(begin.before());
            assertNull(begin.after());

            WalLog.Rec ins = rs.get(1);
            assertEquals(WalLog.INSERT, ins.type());
            assertEquals("emp", ins.table());
            assertEquals(3, ins.pageId());
            assertEquals(1, ins.slot());
            assertNull(ins.before());
            assertEquals("row-after", new String(ins.after(), StandardCharsets.UTF_8));

            WalLog.Rec upd = rs.get(2);
            assertEquals("row-before", new String(upd.before(), StandardCharsets.UTF_8));
            assertEquals("row-after2", new String(upd.after(), StandardCharsets.UTF_8));

            WalLog.Rec del = rs.get(3);
            assertEquals("row-before", new String(del.before(), StandardCharsets.UTF_8));
            assertNull(del.after());

            assertEquals(WalLog.COMMIT, rs.get(4).type());
            // 重开后 lsn 续排（尾部自愈不丢完整记录）
            assertEquals(6, wal.append(WalLog.ABORT, 7, "t", 0, 0, null, null));
        }
    }

    @Test
    void updateRequiresBothMirrors() {
        try (WalLog wal = new WalLog(walFile())) {
            MiniDbException e = assertThrows(MiniDbException.class,
                    () -> wal.append(WalLog.UPDATE, 1, "t", 1, 0, null, new byte[3]));
            assertEquals(MiniDbException.Code.WAL, e.code);
            e = assertThrows(MiniDbException.class,
                    () -> wal.append(WalLog.UPDATE, 1, "t", 1, 0, new byte[3], null));
            assertEquals(MiniDbException.Code.WAL, e.code);
        }
    }

    @Test
    void tornTailHealedOnReopen() throws Exception {
        Path f = walFile();
        try (WalLog wal = new WalLog(f)) {
            wal.append(WalLog.BEGIN, 1, "t", 0, 0, null, null);
            wal.append(WalLog.INSERT, 1, "t", 2, 0, null, new byte[]{9});
        }
        long valid = Files.size(f);
        // 追加一段半条记录（头宣称巨大 payload，实际没有数据）——模拟断电时 OS 缓冲只落了一半
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.APPEND)) {
            ByteBuffer torn = ByteBuffer.allocate(WalLog.HEAD);
            torn.putLong(99);          // lsn
            torn.put(WalLog.INSERT);   // type
            torn.putLong(1);           // txnId
            torn.putInt(1 << 20);      // 谎称 1MB payload
            torn.flip();
            while (torn.hasRemaining()) ch.write(torn);
        }
        assertTrue(Files.size(f) > valid);
        try (WalLog wal = new WalLog(f)) {
            assertEquals(2, wal.readAll().size(), "残缺尾部应被截断，完整记录保留");
            assertEquals(3, wal.append(WalLog.COMMIT, 1, "t", 0, 0, null, null), "lsn 从完整记录续排");
        }
        try (WalLog wal = new WalLog(f)) {
            assertEquals(3, wal.readAll().size());
        }
    }

    @Test
    void truncateClearsLog() throws Exception {
        try (WalLog wal = new WalLog(walFile())) {
            wal.append(WalLog.BEGIN, 1, "t", 0, 0, null, null);
            wal.append(WalLog.COMMIT, 1, "t", 0, 0, null, null);
            wal.sync();
            assertTrue(wal.size() > 0);
            wal.truncate(); // checkpoint
            assertEquals(0, wal.size());
            assertTrue(wal.readAll().isEmpty());
            assertEquals(1, wal.append(WalLog.BEGIN, 2, "t", 0, 0, null, null), "截断后 lsn 重置");
        }
    }
}
