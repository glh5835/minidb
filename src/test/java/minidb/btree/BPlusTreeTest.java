package minidb.btree;

import minidb.btree.BPlusTree.BTreeStats;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** B+ 树：插入分裂/删除借位合并/等值查找/范围扫描/持久化，含边界与故障注入 */
class BPlusTreeTest {
    @TempDir
    Path dir;

    private final List<StorageEngine> engines = new ArrayList<>();

    private BPlusTree newTree(String name) {
        StorageEngine se = new StorageEngine(dir.resolve(name), 512);
        engines.add(se);
        return new BPlusTree(se, 0);
    }

    @org.junit.jupiter.api.AfterEach
    void closeEngines() {
        for (StorageEngine se : engines) se.close();
        engines.clear();
    }

    private static Rid rid(long k) {
        return new Rid((int) (k % 100000), (int) (k % 1000));
    }

    // ---------- 基础查找 ----------

    @Test
    void emptyTreeSearchAndScan() {
        BPlusTree t = newTree("a.db");
        assertNull(t.search(1));
        assertTrue(t.scanAll().isEmpty());
        assertEquals(1, t.height());
        t.validate();
    }

    @Test
    void singleInsertSearch() {
        BPlusTree t = newTree("b.db");
        assertTrue(t.insert(42, rid(42)));
        assertEquals(rid(42), t.search(42));
        assertNull(t.search(41));
        assertNull(t.search(43));
        assertEquals(1, t.stats().leafNodes());
    }

    @Test
    void duplicateInsertRejected() {
        BPlusTree t = newTree("c.db");
        assertTrue(t.insert(7, rid(7)));
        assertFalse(t.insert(7, rid(8)));
        assertEquals(rid(7), t.search(7));
        assertEquals(1, t.stats().totalKeys());
    }

    @Test
    void deleteExistingAndMissing() {
        BPlusTree t = newTree("d.db");
        t.insert(5, rid(5));
        assertFalse(t.delete(6));
        assertTrue(t.delete(5));
        assertNull(t.search(5));
        assertFalse(t.delete(5));
        assertEquals(0, t.stats().totalKeys());
        t.validate();
    }

    @Test
    void deleteToEmptyThenReuse() {
        BPlusTree t = newTree("e.db");
        for (long k = 0; k < 10; k++) t.insert(k, rid(k));
        for (long k = 0; k < 10; k++) assertTrue(t.delete(k));
        assertTrue(t.scanAll().isEmpty());
        assertTrue(t.insert(100, rid(100)));
        assertEquals(rid(100), t.search(100));
        t.validate();
    }

    // ---------- 分裂 ----------

    @Test
    void leafSplitBoundary() {
        // 变长键布局：分裂点由字节容量决定（8 字节键约 185 键/页）。
        // 逐键插入并找出实际分裂点 K，验证 [0,K) 单叶、K+1 键时分裂且数据无损。
        BPlusTree t = newTree("f.db");
        long splitKey = -1;
        for (long k = 0; k < 1000; k++) {
            t.insert(k, rid(k));
            if (splitKey < 0 && t.stats().leafNodes() > 1) { splitKey = k; break; }
        }
        assertTrue(splitKey > 0, "1000 个键内应触发叶分裂");
        assertEquals(2, t.stats().leafNodes());
        assertEquals(1, t.stats().internalNodes());
        assertEquals(splitKey + 1, t.stats().totalKeys());
        for (long k = 0; k <= splitKey; k++) assertEquals(rid(k), t.search(k));
        t.validate();
    }

    @Test
    void multiLevelSplit() {
        BPlusTree t = newTree("g.db");
        // 足够多的键触发两层内部节点（>338 个叶节点才需要第 3 层）
        int n = 100_000;
        for (long k = 0; k < n; k++) t.insert(k * 7, rid(k * 7));
        BTreeStats s = t.stats();
        assertTrue(s.height() >= 3, "树高应 >= 3: " + s.height());
        assertEquals(n, s.totalKeys());
        t.validate();
        for (long k = 0; k < n; k += 97) assertEquals(rid(k * 7), t.search(k * 7));
    }

    @Test
    void sequentialVsRandomInsertSameCount() {
        BPlusTree a = newTree("h1.db");
        for (long k = 0; k < 5000; k++) a.insert(k, rid(k));
        BPlusTree b = newTree("h2.db");
        List<Long> keys = new ArrayList<>();
        for (long k = 0; k < 5000; k++) keys.add(k);
        Collections.shuffle(keys, new Random(42));
        for (long k : keys) b.insert(k, rid(k));
        assertEquals(a.stats().totalKeys(), b.stats().totalKeys());
        assertEquals(a.scanAll(), b.scanAll());
        a.validate();
        b.validate();
    }

    // ---------- 边界键 ----------

    @Test
    void extremeKeyValues() {
        BPlusTree t = newTree("i.db");
        t.insert(Long.MIN_VALUE, rid(1));
        t.insert(Long.MAX_VALUE, rid(2));
        t.insert(0, rid(3));
        t.insert(-1, rid(4));
        assertEquals(rid(1), t.search(Long.MIN_VALUE));
        assertEquals(rid(2), t.search(Long.MAX_VALUE));
        assertEquals(rid(3), t.search(0));
        assertEquals(rid(4), t.search(-1));
        List<Rid> all = t.scanAll();
        assertEquals(List.of(rid(1), rid(4), rid(3), rid(2)), all);
        t.validate();
    }

    @Test
    void adjacentKeys() {
        BPlusTree t = newTree("j.db");
        for (long k = -100; k <= 100; k++) t.insert(k, rid(k));
        for (long k = -100; k <= 100; k++) assertEquals(rid(k), t.search(k));
        assertNull(t.search(101));
        assertNull(t.search(-101));
    }

    // ---------- 范围扫描 ----------

    @Test
    void rangeScanBasics() {
        BPlusTree t = newTree("k.db");
        for (long k = 0; k < 1000; k++) t.insert(k, rid(k));
        assertEquals(100, t.rangeScan(100, 199).size());
        assertEquals(rid(100), t.rangeScan(100, 199).get(0));
        assertEquals(rid(199), t.rangeScan(100, 199).get(99));
        assertTrue(t.rangeScan(2000, 3000).isEmpty());
        // 逆范围
        assertTrue(t.rangeScan(500, 100).isEmpty());
    }

    @Test
    void rangeScanInclusiveExclusiveBounds() {
        BPlusTree t = newTree("l.db");
        for (long k = 0; k < 100; k++) t.insert(k * 10, rid(k * 10));
        assertEquals(8, t.rangeScan(10, false, 90, true).size());  // 20..90
        assertEquals(8, t.rangeScan(10, true, 90, false).size());  // 10..80
        assertEquals(7, t.rangeScan(10, false, 90, false).size()); // 20..80
        assertEquals(99, t.rangeScan(0, false, Long.MAX_VALUE, true).size());
        assertTrue(t.rangeScan(Long.MIN_VALUE, true, 0, false).isEmpty());
    }

    @Test
    void rangeScanAcrossLeafBoundaries() {
        BPlusTree t = newTree("m.db");
        for (long k = 0; k < 2000; k++) t.insert(k, rid(k));
        assertTrue(t.stats().leafNodes() > 3);
        List<Rid> r = t.rangeScan(500, 1499);
        assertEquals(1000, r.size());
        assertEquals(rid(500), r.get(0));
        assertEquals(rid(1499), r.get(999));
        // 缺口范围
        for (long k = 1000; k < 1500; k++) t.delete(k);
        List<Rid> r2 = t.rangeScan(500, 1999);
        assertEquals(1000, r2.size());
    }

    @Test
    void scanAllMatchesRange() {
        BPlusTree t = newTree("n.db");
        List<Long> keys = new ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < 3000; i++) {
            long k = rnd.nextInt(1_000_000);
            keys.add(k);
            t.insert(k, rid(k));
        }
        TreeMap<Long, Rid> map = new TreeMap<>();
        for (long k : keys) map.put(k, rid(k)); // 唯一索引：重复 key 被拒绝
        List<Rid> all = t.scanAll();
        assertEquals(map.size(), all.size());
        assertEquals(map.size(), t.stats().totalKeys());
        Iterator<Rid> it = all.iterator();
        for (Map.Entry<Long, Rid> e : map.entrySet()) {
            assertTrue(it.hasNext());
            assertEquals(e.getValue(), it.next());
        }
    }

    // ---------- 删除：借位与合并 ----------

    @Test
    void deleteTriggersMergeOrBorrow() {
        BPlusTree t = newTree("o.db");
        int n = 2000;
        for (long k = 0; k < n; k++) t.insert(k, rid(k));
        int leavesBefore = t.stats().leafNodes();
        assertTrue(leavesBefore > 2);
        // 删除一半，观察结构收缩但不破坏正确性
        for (long k = 0; k < n; k += 2) assertTrue(t.delete(k));
        t.validate();
        assertEquals(n / 2, t.stats().totalKeys());
        for (long k = 0; k < n; k++) {
            if (k % 2 == 0) assertNull(t.search(k));
            else assertEquals(rid(k), t.search(k));
        }
        assertTrue(t.stats().leafNodes() <= leavesBefore);
    }

    @Test
    void deleteAllShrinksToSingleLeaf() {
        BPlusTree t = newTree("p.db");
        int n = 5000;
        for (long k = 0; k < n; k++) t.insert(k, rid(k));
        assertTrue(t.height() >= 2);
        for (long k = n - 1; k >= 0; k--) assertTrue(t.delete(k));
        BTreeStats s = t.stats();
        assertEquals(1, s.height());
        assertEquals(1, s.leafNodes());
        assertEquals(0, s.internalNodes());
        assertTrue(t.scanAll().isEmpty());
        t.validate();
    }

    @Test
    void deleteRootShrinkChain() {
        BPlusTree t = newTree("q.db");
        int n = 80_000;
        for (long k = 0; k < n; k++) t.insert(k, rid(k));
        int h0 = t.height();
        assertTrue(h0 >= 3, "树高应 >= 3: " + h0);
        // 从小到大删除：触发多层收缩
        for (long k = 0; k < n; k++) assertTrue(t.delete(k));
        assertEquals(1, t.height());
        t.validate();
    }

    @Test
    void insertAfterMassiveDelete() {
        BPlusTree t = newTree("r.db");
        for (long k = 0; k < 10_000; k++) t.insert(k, rid(k));
        for (long k = 0; k < 10_000; k++) t.delete(k);
        for (long k = 10_000; k < 12_000; k++) t.insert(k, rid(k));
        assertEquals(2000, t.stats().totalKeys());
        for (long k = 10_000; k < 12_000; k++) assertEquals(rid(k), t.search(k));
        t.validate();
    }

    // ---------- 持久化 ----------

    @Test
    void persistenceAcrossReopen() {
        Path f = dir.resolve("s.db");
        int rootPage;
        int n = 10_000;
        try (StorageEngine se = new StorageEngine(f, 512)) {
            BPlusTree t = new BPlusTree(se, 0);
            for (long k = 0; k < n; k++) t.insert(k * 3, rid(k * 3));
            rootPage = t.rootPage();
            se.flush();
        }
        try (StorageEngine se = new StorageEngine(f, 512)) {
            BPlusTree t = new BPlusTree(se, rootPage);
            assertEquals(n, t.stats().totalKeys());
            for (long k = 0; k < n; k += 13) assertEquals(rid(k * 3), t.search(k * 3));
            assertNull(t.search(1));
            // 重开后可继续增删
            assertTrue(t.insert(1, rid(1)));
            assertTrue(t.delete(0));
            t.validate();
        }
    }

    @Test
    void persistenceAfterDeletes() {
        Path f = dir.resolve("t.db");
        int rootPage;
        try (StorageEngine se = new StorageEngine(f, 512)) {
            BPlusTree t = new BPlusTree(se, 0);
            for (long k = 0; k < 8000; k++) t.insert(k, rid(k));
            for (long k = 0; k < 4000; k++) t.delete(k);
            rootPage = t.rootPage();
            se.flush();
        }
        try (StorageEngine se = new StorageEngine(f, 512)) {
            BPlusTree t = new BPlusTree(se, rootPage);
            assertEquals(4000, t.stats().totalKeys());
            for (long k = 4000; k < 8000; k++) assertEquals(rid(k), t.search(k));
            t.validate();
        }
    }

    // ---------- 统计 ----------

    @Test
    void statsReportHeightAndUtilization() {
        BPlusTree t = newTree("u.db");
        for (long k = 0; k < 10_000; k++) t.insert(k, rid(k));
        BTreeStats s = t.stats();
        assertEquals(10_000, s.totalKeys());
        assertTrue(s.height() >= 2);
        // 8 字节键单页约 185 键，10_000 键至少 ~54 叶；字节利用率顺序插入应偏高
        assertTrue(s.leafNodes() >= 10_000 / 185, "leafNodes=" + s.leafNodes());
        assertTrue(s.avgLeafUtilization() > 0.3 && s.avgLeafUtilization() <= 1.0,
                "util=" + s.avgLeafUtilization());
    }

    // ---------- 故障注入 ----------

    @Test
    void corruptedNodeTypeFaults() {
        Path f = dir.resolve("v.db");
        int rootPage;
        try (StorageEngine se = new StorageEngine(f, 64)) {
            BPlusTree t = new BPlusTree(se, 0);
            for (long k = 0; k < 10; k++) t.insert(k, rid(k));
            rootPage = t.rootPage();
            se.flush();
        }
        try (StorageEngine se = new StorageEngine(f, 64)) {
            BPlusTree t = new BPlusTree(se, rootPage); // 构造时类型还正常
            // 破坏根页类型 → 后续访问应报 BTREE 错（故障注入）
            se.pool().getPage(rootPage).data()[0] = minidb.storage.Page.Type.CATALOG.id;
            se.pool().unpin(rootPage, true);
            MiniDbException e = assertThrows(MiniDbException.class, () -> t.search(1));
            assertEquals(MiniDbException.Code.BTREE, e.code);
        }
    }

    @Test
    void nonBtreePageRejectedAsRoot() {
        try (StorageEngine se = new StorageEngine(dir.resolve("w.db"), 64)) {
            int pid = se.allocPage(); // 保留页类型 FREE
            MiniDbException e = assertThrows(MiniDbException.class, () -> new BPlusTree(se, pid));
            assertEquals(MiniDbException.Code.BTREE, e.code);
        }
    }

    @Test
    void smallBufferPoolStillWorks() {
        try (StorageEngine se = new StorageEngine(dir.resolve("x.db"), 8)) {
            BPlusTree t = new BPlusTree(se, 0);
            for (long k = 0; k < 3000; k++) t.insert(k, rid(k));
            for (long k = 0; k < 3000; k += 2) t.delete(k);
            t.validate();
            for (long k = 1; k < 3000; k += 2) assertEquals(rid(k), t.search(k));
        }
    }

    // ---------- 验证（任务书阶段2） ----------

    @Test
    @Timeout(600)
    void randomInsert100kMatchesTreeMap() {
        BPlusTree t = newTree("verify1.db");
        TreeMap<Long, Rid> reference = new TreeMap<>();
        Random rnd = new Random(2024);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            long k = rnd.nextLong(1_000_000_000L);
            Rid v = rid(k);
            boolean treeOk = t.insert(k, v);
            Rid prev = reference.put(k, v);
            assertEquals(prev == null, treeOk, "插入结果不一致 key=" + k);
        }
        assertEquals(reference.size(), t.stats().totalKeys());
        // 逐条等值比对
        for (Map.Entry<Long, Rid> e : reference.entrySet())
            assertEquals(e.getValue(), t.search(e.getKey()), "search 不一致 key=" + e.getKey());
        // 不存在的键
        for (long miss : new long[]{-1, 1_000_000_000L, 2_000_000_000L})
            assertNull(t.search(miss));
        // 全序扫描一致
        List<Rid> scanned = t.scanAll();
        assertEquals(reference.size(), scanned.size());
        Iterator<Rid> it = scanned.iterator();
        for (Map.Entry<Long, Rid> e : reference.entrySet()) assertEquals(e.getValue(), it.next());
        // 范围扫描与 TreeMap.subMap 一致
        for (int i = 0; i < 20; i++) {
            long a = rnd.nextLong(1_000_000_000L), b = rnd.nextLong(1_000_000_000L);
            if (a > b) { long tmp = a; a = b; b = tmp; }
            List<Long> expect = new ArrayList<>(reference.subMap(a, true, b, true).keySet());
            List<Rid> got = t.rangeScan(a, b);
            assertEquals(expect.size(), got.size());
            for (int j = 0; j < expect.size(); j++)
                assertEquals(reference.get(expect.get(j)), got.get(j));
        }
        t.validate();
    }

    @Test
    @Timeout(600)
    void randomDelete50kThenMatchesTreeMap() {
        BPlusTree t = newTree("verify2.db");
        TreeMap<Long, Rid> reference = new TreeMap<>();
        Random rnd = new Random(9527);
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            long k = rnd.nextLong(2_000_000_000L);
            t.insert(k, rid(k));
            reference.put(k, rid(k));
            inserted.add(k);
        }
        // 随机删 5 万（去重后可能不足 5 万个不同键，取前 5 万个位置）
        Collections.shuffle(inserted, new Random(777));
        int deleted = 0;
        for (int i = 0; i < inserted.size() && deleted < 50_000; i++) {
            long k = inserted.get(i);
            if (reference.containsKey(k)) {
                boolean treeOk = t.delete(k);
                reference.remove(k);
                assertTrue(treeOk);
                deleted++;
            }
        }
        assertEquals(reference.size(), t.stats().totalKeys());
        // 全量比对
        for (Map.Entry<Long, Rid> e : reference.entrySet())
            assertEquals(e.getValue(), t.search(e.getKey()), "删除后 search 不一致 key=" + e.getKey());
        List<Rid> scanned = t.scanAll();
        assertEquals(reference.size(), scanned.size());
        Iterator<Rid> it = scanned.iterator();
        for (Map.Entry<Long, Rid> e : reference.entrySet()) assertEquals(e.getValue(), it.next());
        // 再插入仍工作
        t.insert(-12345, rid(-12345));
        assertEquals(rid(-12345), t.search(-12345));
        t.validate();
    }
}
