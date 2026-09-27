package minidb.perf;

import minidb.btree.BPlusTree;
import minidb.exec.Executor;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Row;
import minidb.storage.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 阶段4压测：100 万行数据，对比走索引 vs 全表扫描的真实耗时。
 * 运行：java -cp target/classes minidb.perf.Stress1M [数据目录]
 */
public final class Stress1M {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/stress");
        Files.createDirectories(dir);
        Path dbFile = dir.resolve("stress1m.db");
        if (Files.exists(dbFile)) Files.delete(dbFile);

        int N = 1_000_000;
        System.out.println("== MiniDB 100 万行压测 ==");
        long t0 = System.nanoTime();
        Database db = Database.open(dbFile, 4096);
        Table t = db.createTable("lineitem", List.of(
                Column.fixed("id", ColumnType.INT),
                Column.fixed("k", ColumnType.INT),
                Column.fixed("val", ColumnType.BIGINT)));
        Random rnd = new Random(42);
        for (int i = 0; i < N; i++) {
            t.insert(new Object[]{i, rnd.nextInt(N / 10), (long) i * 3});
        }
        long insertMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("插入 %d 行（Table API + BufferPool 4096 页）: %d ms（%.0f 行/秒）%n",
                N, insertMs, N * 1000.0 / insertMs);
        System.out.printf("表页数 %d，缓冲池 %d 页%n", t.pageCount(), db.engine().pool().capacity());

        // 建索引（全表扫描构建）
        long t1 = System.nanoTime();
        db.createIndex("idx_id", "lineitem", "id");
        long buildMs = (System.nanoTime() - t1) / 1_000_000;
        BPlusTree tree = db.getIndex("idx_id");
        System.out.printf("CREATE INDEX（10万级随机键 B+ 树构建）: %d ms，树高 %d，叶节点 %d，利用率 %.1f%%%n",
                buildMs, tree.height(), tree.stats().leafNodes(),
                tree.stats().avgLeafUtilization() * 100);

        // SQL 层点查：走索引
        int pointQ = 2_000;
        Executor ex = new Executor(db);
        long t2 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < pointQ; i++) {
            int key = rnd.nextInt(N);
            hits += ex.execute("SELECT val FROM lineitem WHERE id = " + key).rowCount();
        }
        long idxPointMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.printf("SQL 点查 × %d（走索引）: %d ms（%.3f ms/次，命中 %d）%n",
                pointQ, idxPointMs, idxPointMs / (double) pointQ, hits);

        // SQL 点查：全表扫描（无索引列 k）
        int seqQ = 5;
        long t3 = System.nanoTime();
        int seqHits = 0;
        for (int i = 0; i < seqQ; i++) {
            int key = rnd.nextInt(N / 10);
            seqHits += ex.execute("SELECT COUNT(*) FROM lineitem WHERE k = " + key).rowCount();
        }
        long seqPointMs = (System.nanoTime() - t3) / 1_000_000;
        System.out.printf("SQL 点查 × %d（无索引列全表扫描）: %d ms（%.0f ms/次，命中 %d）%n",
                seqQ, seqPointMs, seqPointMs / (double) seqQ, seqHits);

        // 范围查询：走索引
        long t4 = System.nanoTime();
        int rangeQ = 500;
        long rangeSum = 0;
        for (int i = 0; i < rangeQ; i++) {
            int a = rnd.nextInt(N - 1000);
            rangeSum += ex.execute("SELECT COUNT(*) FROM lineitem WHERE id >= " + a
                    + " AND id < " + (a + 1000)).rows().get(0)[0] instanceof Integer n ? n : 0;
        }
        long idxRangeMs = (System.nanoTime() - t4) / 1_000_000;
        System.out.printf("SQL 范围查 × %d（各 1000 行，走索引）: %d ms（%.3f ms/次，合计 %d 行）%n",
                rangeQ, idxRangeMs, idxRangeMs / (double) rangeQ, rangeSum);

        // 范围查询：全表扫描
        long t5 = System.nanoTime();
        int seqRangeQ = 5;
        long seqRangeSum = 0;
        for (int i = 0; i < seqRangeQ; i++) {
            seqRangeSum += ex.execute("SELECT COUNT(*) FROM lineitem WHERE k >= " + (i * 10)
                    + " AND k < " + (i * 10 + 10)).rows().get(0)[0] instanceof Integer n ? n : 0;
        }
        long seqRangeMs = (System.nanoTime() - t5) / 1_000_000;
        System.out.printf("SQL 范围查 × %d（全表扫描）: %d ms（%.0f ms/次，合计 %d 行）%n",
                seqRangeQ, seqRangeMs, seqRangeMs / (double) seqRangeQ, seqRangeSum);

        // 全表扫描一遍的代价（Table API 层）
        long t6 = System.nanoTime();
        long sum = 0;
        int cnt = 0;
        for (Iterator<Row> it = t.scan(); it.hasNext(); ) {
            sum += (Long) it.next().values()[2];
            cnt++;
        }
        long scanMs = (System.nanoTime() - t6) / 1_000_000;
        System.out.printf("Table API 全表扫描 %d 行: %d ms（%.0f 万行/秒，校验和 %d）%n",
                cnt, scanMs, cnt / 10000.0 / Math.max(1, scanMs) * 1000, sum);

        db.close();
        System.out.println("== 压测完成 ==");
    }
}
