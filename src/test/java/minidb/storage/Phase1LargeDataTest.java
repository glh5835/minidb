package minidb.storage;

import minidb.common.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务书阶段1验证：插入 10 万条记录再全部读回，逐字段比对一致。
 * 定长表与变长表各 10 万条。
 */
class Phase1LargeDataTest {
    @TempDir
    Path dir;

    private static final int N = 100_000;

    @Test
    @Timeout(300)
    void fixedTable100kRoundtrip() {
        try (Database d = Database.open(dir.resolve("fixed.db"), 4096)) {
            Table t = d.createTable("big", List.of(
                    Column.fixed("id", ColumnType.INT),
                    Column.fixed("ts", ColumnType.BIGINT),
                    Column.fixed("ratio", ColumnType.DOUBLE)));
            for (int i = 0; i < N; i++) {
                t.insert(new Object[]{i, (long) i * 7919, i * 0.25});
            }
            assertEquals(N, t.rowCount());
            long sum = 0;
            int count = 0;
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Row r = it.next();
                Object[] v = r.values();
                assertEquals(count, v[0]);
                assertEquals((long) count * 7919, v[1]);
                assertEquals(count * 0.25, v[2]);
                sum += (Integer) v[0];
                count++;
            }
            assertEquals(N, count);
            assertEquals((long) N * (N - 1) / 2, sum);
        }
        // 重开再校验一遍（持久化）
        try (Database d = Database.open(dir.resolve("fixed.db"), 4096)) {
            Table t = d.getTable("big");
            assertEquals(N, t.rowCount());
            int count = 0;
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Object[] v = it.next().values();
                assertEquals(count, v[0]);
                assertEquals((long) count * 7919, v[1]);
                assertEquals(count * 0.25, v[2]);
                count++;
            }
            assertEquals(N, count);
        }
    }

    @Test
    @Timeout(300)
    void varTable100kRoundtrip() {
        String[] pool = {"apple", "banana-cherry", "中文数据", "", "x", "a much longer value for testing"};
        try (Database d = Database.open(dir.resolve("var.db"), 4096)) {
            Table t = d.createTable("big", List.of(
                    Column.fixed("id", ColumnType.INT),
                    new Column("name", ColumnType.VARCHAR, 64)));
            for (int i = 0; i < N; i++) {
                t.insert(new Object[]{i, pool[i % pool.length]});
            }
            assertEquals(N, t.rowCount());
            assertTrue(t.pageCount() > 1);
            // best-fit 选页会把记录散布到各页，扫描顺序不保证等于插入顺序，
            // 因此逐条校验 (id, name) 配对 + 用集合确认无缺无重
            boolean[] seen = new boolean[N];
            int count = 0;
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Object[] v = it.next().values();
                int id = (Integer) v[0];
                assertFalse(seen[id], "重复 id " + id);
                seen[id] = true;
                assertEquals(pool[id % pool.length], v[1]);
                count++;
            }
            assertEquals(N, count);
            for (int i = 0; i < N; i++) assertTrue(seen[i], "缺少 id " + i);
        }
        try (Database d = Database.open(dir.resolve("var.db"), 4096)) {
            Table t = d.getTable("big");
            int count = 0;
            boolean[] seen = new boolean[N];
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Object[] v = it.next().values();
                int id = (Integer) v[0];
                assertFalse(seen[id]);
                seen[id] = true;
                assertEquals(pool[id % pool.length], v[1]);
                count++;
            }
            assertEquals(N, count);
        }
    }

    @Test
    @Timeout(300)
    void mixedWorkload100kSurvivesReopen() {
        // 10 万条混合负载：插入 + 删除 + 更新，最终状态可完整复现
        try (Database d = Database.open(dir.resolve("mix.db"), 4096)) {
            Table t = d.createTable("m", List.of(
                    Column.fixed("id", ColumnType.INT),
                    new Column("note", ColumnType.VARCHAR, 32)));
            Rid[] rids = new Rid[N];
            for (int i = 0; i < N; i++) rids[i] = t.insert(new Object[]{i, "note" + i});
            for (int i = 0; i < N; i += 2) t.delete(rids[i]);          // 删一半
            for (int i = 1; i < N; i += 2) t.update(rids[i], new Object[]{i, "updated" + i});
            for (int i = 0; i < 1000; i++) t.insert(new Object[]{1_000_000 + i, "extra" + i});
            assertEquals(N / 2 + 1000, t.rowCount());
        }
        try (Database d = Database.open(dir.resolve("mix.db"), 4096)) {
            Table t = d.getTable("m");
            assertEquals(N / 2 + 1000, t.rowCount());
            int updated = 0, extra = 0;
            for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
                Object[] v = it.next().values();
                int id = (Integer) v[0];
                if (id >= 1_000_000) {
                    assertEquals("extra" + (id - 1_000_000), v[1]);
                    extra++;
                } else {
                    assertEquals("updated" + id, v[1]);
                    updated++;
                }
            }
            assertEquals(N / 2, updated);
            assertEquals(1000, extra);
        }
    }
}
