package minidb.bench;

import minidb.btree.BPlusTree;
import minidb.common.Rid;
import minidb.exec.Executor;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Row;
import minidb.storage.Table;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** 阶段4 JMH 基准。运行：org.openjdk.jmh.Main（见 PERF.md）。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DbBenchmarks {
    private Path dbFile;
    private Database db;
    private Executor ex;
    private Table table;
    private BPlusTree tree;
    private Random rnd;

    private static final int ROWS = 100_000;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dbFile = Files.createTempDirectory("minidb-bench").resolve("bench.db");
        Files.deleteIfExists(dbFile);
        db = Database.open(dbFile, 4096);
        ex = new Executor(db);
        table = db.createTable("b", List.of(
                Column.fixed("id", ColumnType.INT),
                Column.fixed("v", ColumnType.BIGINT)));
        rnd = new Random(7);
        for (int i = 0; i < ROWS; i++) {
            table.insert(new Object[]{i, (long) i * 3});
        }
        db.createIndex("b_id", "b", "id");
        tree = db.getIndex("b_id");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        db.close();
    }

    @Benchmark
    public Rid bPlusTreePointSearch() {
        return tree.search(rnd.nextInt(ROWS));
    }

    @Benchmark
    public boolean bPlusTreeInsertDelete() {
        long k = 1_000_000_000L + rnd.nextInt(1_000_000);
        boolean ok = tree.insert(k, new Rid((int) (k % 1000), 0));
        tree.delete(k);
        return ok;
    }

    @Benchmark
    public int tableInsertDeleteRow() {
        int k = 100_000_000 + rnd.nextInt(1_000_000);
        Rid rid = table.insert(new Object[]{k, (long) k});
        table.delete(rid);
        return rid.slot();
    }

    @Benchmark
    public Object sqlPointQueryViaIndex() {
        return ex.execute("SELECT v FROM b WHERE id = " + rnd.nextInt(ROWS)).rows().get(0)[0];
    }

    @Benchmark
    public Iterator<Row> tableFullScan10k() {
        // 全表扫描前 1 万行（通过 LIMIT 语义的等价 API 采样：直接顺序拉）
        Iterator<Row> it = table.scan();
        int n = 10_000;
        Row last = null;
        while (n-- > 0 && it.hasNext()) last = it.next();
        return it;
    }

    @Benchmark
    public List<Object[]> sqlRangeViaIndex() {
        int a = rnd.nextInt(ROWS - 1000);
        return ex.execute("SELECT COUNT(*) FROM b WHERE id >= " + a + " AND id < " + (a + 1000)).rows();
    }
}
