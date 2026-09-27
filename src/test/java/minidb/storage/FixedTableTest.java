package minidb.storage;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** 定长记录表：增删改查 + 页溢出 + 空间复用 + 故障注入 */
class FixedTableTest {
    @TempDir
    Path dir;

    private Database db(String name) {
        return Database.open(dir.resolve(name));
    }

    private Table t(Database d, String name) {
        return d.createTable(name, List.of(
                Column.fixed("id", ColumnType.INT),
                Column.fixed("bal", ColumnType.BIGINT),
                Column.fixed("ratio", ColumnType.DOUBLE)));
    }

    private Object[] row(int id) {
        return new Object[]{id, (long) id * 10, id * 0.5};
    }

    @Test
    void insertGetScan() {
        try (Database d = db("a.db")) {
            Table t = t(d, "t");
            Rid r1 = t.insert(row(1));
            Rid r2 = t.insert(row(2));
            assertEquals(1, t.get(r1)[0]);
            assertEquals(2, t.get(r2)[0]);
            assertEquals(2, t.rowCount());
            Iterator<Row> it = t.scan();
            assertEquals(1, it.next().values()[0]);
            assertEquals(2, it.next().values()[0]);
            assertFalse(it.hasNext());
        }
    }

    @Test
    void scanValuesCarryRid() {
        try (Database d = db("b.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(5));
            Row got = t.scan().next();
            assertEquals(r, got.rid());
        }
    }

    @Test
    void deleteRemovesRow() {
        try (Database d = db("c.db")) {
            Table t = t(d, "t");
            Rid r1 = t.insert(row(1));
            Rid r2 = t.insert(row(2));
            t.delete(r1);
            assertEquals(1, t.rowCount());
            assertEquals(2, t.get(r2)[0]);
            assertThrows(MiniDbException.class, () -> t.get(r1));
            assertThrows(MiniDbException.class, () -> t.delete(r1));
        }
    }

    @Test
    void deletedSlotReused() {
        try (Database d = db("d.db")) {
            Table t = t(d, "t");
            Rid r1 = t.insert(row(1));
            Rid r2 = t.insert(row(2));
            t.delete(r1);
            Rid r3 = t.insert(row(3));
            assertEquals(r1, r3); // 槽位复用
            assertEquals(3, t.get(r3)[0]);
            assertEquals(2, t.rowCount());
            assertEquals(2, t.get(r2)[0]);
        }
    }

    @Test
    void updateInPlaceKeepsRid() {
        try (Database d = db("e.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1));
            Rid back = t.update(r, row(99));
            assertEquals(r, back);
            assertArrayEquals(row(99), t.get(r));
        }
    }

    @Test
    void spillsToSecondPageWhenFull() {
        try (Database d = db("f.db")) {
            Table t = t(d, "t"); // recordSize=20 → capacity=(4064*8)/(161)=201
            FixedTable ft = (FixedTable) t;
            int cap = ft.capacity();
            for (int i = 0; i < cap + 5; i++) t.insert(row(i));
            assertEquals(2, t.pageCount());
            assertEquals(cap + 5, t.rowCount());
            // 扫描顺序：第 1 页填满后第 2 页
            Iterator<Row> it = t.scan();
            assertEquals(0, it.next().values()[0]);
            for (int i = 1; i < cap; i++) assertEquals(i, it.next().values()[0]);
            assertEquals(cap, it.next().values()[0]); // 第 2 页第一条
        }
    }

    @Test
    void emptySecondPageReleasedAfterDelete() {
        try (Database d = db("g.db")) {
            Table t = t(d, "t");
            FixedTable ft = (FixedTable) t;
            int cap = ft.capacity();
            java.util.List<Rid> rids = new java.util.ArrayList<>();
            for (int i = 0; i < cap + 1; i++) rids.add(t.insert(row(i)));
            assertEquals(2, t.pageCount());
            // 删掉第 2 页的所有行（最后一条，槽序在后）
            t.delete(rids.get(cap));
            assertEquals(1, t.pageCount());
            assertEquals(cap, t.rowCount());
        }
    }

    @Test
    void persistenceAcrossReopen() {
        try (Database d = db("h.db")) {
            Table t = t(d, "t");
            t.insert(row(1));
            t.insert(row(2));
        }
        try (Database d = db("h.db")) {
            Table t = d.getTable("t");
            assertEquals(2, t.rowCount());
            Iterator<Row> it = t.scan();
            assertEquals(1, it.next().values()[0]);
            assertEquals(2, it.next().values()[0]);
        }
    }

    @Test
    void reopenRebuildsFreeMap() {
        // 删除后关闭再打开，插入应复用空槽
        try (Database d = db("i.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(row(1));
            t.insert(row(2));
            t.delete(r);
        }
        try (Database d = db("i.db")) {
            Table t = d.getTable("t");
            Rid r3 = t.insert(row(3));
            assertEquals(0, r3.slot());
            assertEquals(2, t.rowCount());
        }
    }

    @Test
    void invalidRidFaults() {
        try (Database d = db("j.db")) {
            Table t = t(d, "t");
            t.insert(row(1));
            assertThrows(MiniDbException.class, () -> t.get(new Rid(999, 0)));
            assertThrows(MiniDbException.class, () -> t.get(new Rid(0, 0)));
            assertThrows(MiniDbException.class, () -> t.get(new Rid(1, -1)));
            assertThrows(MiniDbException.class, () -> t.delete(new Rid(999, 0)));
            assertThrows(MiniDbException.class, () -> t.update(new Rid(999, 0), row(1)));
        }
    }

    @Test
    void wrongRowWidthRejected() {
        try (Database d = db("k.db")) {
            Table t = t(d, "t");
            assertThrows(MiniDbException.class, () -> t.insert(new Object[]{1, 2L}));
        }
    }

    @Test
    void scanEmptyTable() {
        try (Database d = db("l.db")) {
            Table t = t(d, "t");
            assertFalse(t.scan().hasNext());
            assertEquals(0, t.rowCount());
        }
    }

    @Test
    void boundaryExactCapacityFill() {
        try (Database d = db("m.db")) {
            Table t = t(d, "t");
            FixedTable ft = (FixedTable) t;
            for (int i = 0; i < ft.capacity(); i++) t.insert(row(i));
            assertEquals(1, t.pageCount());
            t.insert(row(999));
            assertEquals(2, t.pageCount());
        }
    }

    @Test
    void largeIdsSurviveEncoding() {
        try (Database d = db("n.db")) {
            Table t = t(d, "t");
            Rid r = t.insert(new Object[]{Integer.MIN_VALUE, Long.MAX_VALUE, -Double.MAX_VALUE});
            Object[] back = t.get(r);
            assertEquals(Integer.MIN_VALUE, back[0]);
            assertEquals(Long.MAX_VALUE, back[1]);
            assertEquals(-Double.MAX_VALUE, back[2]);
        }
    }

    @Test
    void manyDeletesAndReinserts() {
        try (Database d = db("o.db")) {
            Table t = t(d, "t");
            Rid[] rids = new Rid[50];
            for (int i = 0; i < 50; i++) rids[i] = t.insert(row(i));
            for (int i = 0; i < 50; i += 2) t.delete(rids[i]);
            assertEquals(25, t.rowCount());
            for (int i = 0; i < 25; i++) t.insert(row(1000 + i));
            assertEquals(50, t.rowCount());
            // 全部读回校验无重复
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Iterator<Row> it = t.scan(); it.hasNext(); )
                assertTrue(seen.add((Integer) it.next().values()[0]));
            assertEquals(50, seen.size());
        }
    }
}
