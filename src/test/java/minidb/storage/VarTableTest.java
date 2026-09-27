package minidb.storage;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 变长记录表：槽位数组/空洞/压缩/更新移动，含故障注入 */
class VarTableTest {
    @TempDir
    Path dir;

    private Table t(Database d, String name) {
        return d.createTable(name, List.of(
                Column.fixed("id", ColumnType.INT),
                new Column("name", ColumnType.VARCHAR, 64)));
    }

    private Database db(String name) {
        return Database.open(dir.resolve(name));
    }

    private static Object[] row(int id, String name) {
        return new Object[]{id, name};
    }

    @Test
    void insertGetScan() {
        try (Database d = db("a.db")) {
            Table t = t(d, "t");
            Rid r1 = t.insert(row(1, "alice"));
            Rid r2 = t.insert(row(2, "bob"));
            assertArrayEquals(row(1, "alice"), t.get(r1));
            assertArrayEquals(row(2, "bob"), t.get(r2));
            assertEquals(2, t.rowCount());
        }
    }

    @Test
    void varcharBoundaryLengths() {
        try (Database d = db("b.db")) {
            Table t = t(d, "t");
            Rid a = t.insert(row(1, "x")); // 1 字节
            Rid b = t.insert(row(2, "y".repeat(64))); // 恰好上限
            assertEquals("x", t.get(a)[1]);
            assertEquals(64, ((String) t.get(b)[1]).length());
            assertThrows(MiniDbException.class, () -> t.insert(row(3, "z".repeat(65))));
        }
    }

    @Test
    void deleteMakesHoleAndReusesSpace() {
        try (Database d = db("c.db")) {
            Table t = t(d, "t");
            Rid r1 = t.insert(row(1, "alice"));
            t.insert(row(2, "bob"));
            long freeBefore = t.totalFreeBytes();
            t.delete(r1);
            assertTrue(t.totalFreeBytes() > freeBefore);
            Rid r3 = t.insert(row(3, "carol")); // 复用槽 0
            assertEquals(0, r3.slot());
            assertEquals(2, t.rowCount());
        }
    }

    @Test
    void ridStableAcrossCompaction() {
        try (Database d = db("d.db")) {
            Table t = t(d, "t");
            Rid a = t.insert(row(1, "A".repeat(60)));
            Rid b = t.insert(row(2, "B".repeat(60)));
            Rid c = t.insert(row(3, "C".repeat(60)));
            t.delete(a);
            t.delete(b);
            // 产生大量空洞后插入大记录触发压缩
            t.insert(row(4, "D".repeat(64)));
            assertArrayEquals(row(3, "C".repeat(60)), t.get(c)); // 槽号不变
            assertEquals(2, t.rowCount());
        }
    }

    @Test
    void compactionActuallyReclaimsHoles() {
        try (Database d = db("e.db")) {
            Table t = t(d, "t");
            java.util.Map<Integer, Rid> byId = new java.util.HashMap<>();
            for (int i = 0; i < 30; i++) byId.put(i, t.insert(row(i, "N".repeat(50))));
            int pages1 = t.pageCount();
            // 删除 25 条制造碎片（先收集，避免边扫描边删）
            for (int i = 0; i < 30; i++) {
                if (i % 30 < 25) t.delete(byId.remove(i));
            }
            t.insert(row(999, "X".repeat(64))); // 应触发压缩，页数不增
            assertEquals(pages1, t.pageCount());
            assertEquals(6, t.rowCount());
            for (var e : byId.entrySet()) {
                Object[] got = t.get(e.getValue());
                assertEquals(e.getKey(), got[0]);
            }
        }
    }

    @Test
    void updateGrowWithinPageKeepsRid() {
        try (Database d = db("f.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1, "a"));
            Rid back = t.update(r, row(1, "much longer name now"));
            assertEquals(r, back);
            assertArrayEquals(row(1, "much longer name now"), t.get(r));
            assertEquals(1, t.rowCount());
        }
    }

    @Test
    void updateShrinkKeepsRid() {
        try (Database d = db("g.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1, "a very long name here"));
            t.update(r, row(1, "tiny"));
            assertEquals(4, ((String) t.get(r)[1]).length());
        }
    }

    @Test
    void updateOverflowMovesToNewPage() {
        try (Database d = db("h.db")) {
            Table t = d.createTable("t", List.of(
                    Column.fixed("id", ColumnType.INT),
                    new Column("name", ColumnType.VARCHAR, 200)));
            // 把第一页填满（每条 4+2+50=56B + 4B 槽 = 60B，一页约 67 条）
            for (int i = 0; i < 67; i++) t.insert(row(i, "N".repeat(50)));
            assertEquals(1, t.pageCount());
            // 更新第一条为远超原页连续空间的大记录：必须移动到新页
            Row first = t.scan().next();
            Rid old = first.rid();
            Rid moved = t.update(old, row(0, "X".repeat(150)));
            assertNotEquals(old, moved);
            assertArrayEquals(row(0, "X".repeat(150)), t.get(moved));
            assertThrows(MiniDbException.class, () -> t.get(old));
            assertEquals(67, t.rowCount());
            assertEquals(2, t.pageCount());
            // 扫描恰好 67 行且无重复
            Set<Integer> ids = new HashSet<>();
            for (Iterator<Row> it2 = t.scan(); it2.hasNext(); ) assertTrue(ids.add((Integer) it2.next().values()[0]));
            assertEquals(67, ids.size());
        }
    }

    @Test
    void manyRecordsSpanPages() {
        try (Database d = db("i.db")) {
            Table t = t(d, "t");
            for (int i = 0; i < 500; i++) t.insert(row(i, "name" + i));
            assertTrue(t.pageCount() > 1);
            assertEquals(500, t.rowCount());
            Set<Integer> ids = new HashSet<>();
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) assertTrue(ids.add((Integer) it.next().values()[0]));
            assertEquals(500, ids.size());
        }
    }

    @Test
    void persistenceAcrossReopen() {
        try (Database d = db("j.db")) {
            Table t = t(d, "t");
            t.insert(row(1, "中文内容"));
            t.insert(row(2, ""));
        }
        try (Database d = db("j.db")) {
            Table t = d.getTable("t");
            assertEquals(2, t.rowCount());
            Iterator<Row> it = t.scan();
            assertArrayEquals(row(1, "中文内容"), it.next().values());
            assertArrayEquals(row(2, ""), it.next().values());
        }
    }

    @Test
    void emptyLastPageReleased() {
        try (Database d = db("k.db")) {
            Table t = t(d, "t");
            java.util.Map<Integer, Rid> byId = new java.util.HashMap<>();
            for (int i = 0; i < 120; i++) byId.put(i, t.insert(row(i, "N".repeat(30))));
            int pages = t.pageCount();
            assertTrue(pages > 1);
            // 删除后半部分（先收集，避免边扫描边删）→ 空页回收
            for (int i = 60; i < 120; i++) t.delete(byId.get(i));
            assertTrue(t.pageCount() < pages);
            assertEquals(60, t.rowCount());
        }
    }

    @Test
    void invalidRidFaults() {
        try (Database d = db("l.db")) {
            Table t = t(d, "t");
            t.insert(row(1, "a"));
            assertThrows(MiniDbException.class, () -> t.get(new Rid(999, 0)));
            assertThrows(MiniDbException.class, () -> t.get(new Rid(2, 1000)));
            assertThrows(MiniDbException.class, () -> t.delete(new Rid(999, 0)));
            assertThrows(MiniDbException.class, () -> t.update(new Rid(999, 0), row(1, "a")));
        }
    }

    @Test
    void doubleDeleteThrows() {
        try (Database d = db("m.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1, "a"));
            t.delete(r);
            assertThrows(MiniDbException.class, () -> t.delete(r));
        }
    }

    @Test
    void updateMissingRowThrows() {
        try (Database d = db("n.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1, "a"));
            t.delete(r);
            assertThrows(MiniDbException.class, () -> t.update(r, row(1, "b")));
        }
    }

    @Test
    void interleaveDeleteInsertUpdate() {
        try (Database d = db("o.db")) {
            Table t = t(d, "t");
            java.util.Map<Integer, Rid> byId = new java.util.HashMap<>();
            for (int i = 0; i < 200; i++) byId.put(i, t.insert(row(i, "v" + i)));
            for (int i = 0; i < 200; i += 3) {
                t.delete(byId.remove(i));
            }
            for (int i = 0; i < 200; i += 5) {
                if (!byId.containsKey(i)) continue; // 已被上面删除
                t.update(byId.get(i), row(i, "u" + i));
            }
            for (int i = 200; i < 260; i++) byId.put(i, t.insert(row(i, "w" + i)));
            // 校验
            assertEquals(200 - 67 + 60, t.rowCount());
            for (var e : byId.entrySet()) {
                Object[] got = t.get(e.getValue());
                assertEquals(e.getKey(), got[0]);
            }
        }
    }

    @Test
    void longChainScanIntegrity() {
        try (Database d = db("p.db")) {
            Table t = t(d, "t");
            int n = 2000;
            for (int i = 0; i < n; i++) t.insert(row(i, "s" + i));
            int count = 0;
            long sum = 0;
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Row r = it.next();
                sum += (Integer) r.values()[0];
                count++;
            }
            assertEquals(n, count);
            assertEquals((long) n * (n - 1) / 2, sum);
        }
    }
}
