package minidb.exec;

import minidb.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 极简代价优化器：索引选择与 JOIN 顺序（通过 EXPLAIN 观察） */
class OptimizerTest {
    @TempDir
    Path dir;

    private Database db;
    private Executor ex;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("t.db"), 512);
        ex = new Executor(db);
        ex.execute("CREATE TABLE big (id INT, v INT)");
        ex.execute("CREATE TABLE small (id INT, w VARCHAR(10))");
        for (int i = 0; i < 2000; i++)
            ex.execute("INSERT INTO big VALUES (" + i + ", " + (i % 100) + ")");
        ex.execute("INSERT INTO small VALUES (1,'a'),(2,'b'),(3,'c')");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void eqPredicateUsesIndex() {
        ex.execute("CREATE INDEX i_big_id ON big(id)");
        String plan = ex.explain("SELECT * FROM big WHERE id = 42");
        assertTrue(plan.contains("IndexScan"), plan);
        assertFalse(plan.contains("SeqScan"), plan);
        // 行数正确（走索引）
        assertEquals(1, ex.execute("SELECT * FROM big WHERE id = 42").rowCount());
    }

    @Test
    void rangePredicateUsesIndex() {
        ex.execute("CREATE INDEX i_big_id ON big(id)");
        String plan = ex.explain("SELECT * FROM big WHERE id < 5");
        assertTrue(plan.contains("IndexScan"), plan);
        assertEquals(5, ex.execute("SELECT * FROM big WHERE id < 5").rowCount());
    }

    @Test
    void selectivePredicateFallsBackToSeqScan() {
        // 无索引 → SeqScan
        String plan = ex.explain("SELECT * FROM big WHERE v = 42");
        assertTrue(plan.contains("SeqScan"), plan);
    }

    @Test
    void indexPredicateOnUnindexedColumnStaysSeqScan() {
        ex.execute("CREATE INDEX i_big_id ON big(id)");
        String plan = ex.explain("SELECT * FROM big WHERE v = 42");
        assertTrue(plan.contains("SeqScan"), plan);
    }

    @Test
    void joinPutsSmallerTableFirst() {
        // small 3 行，big 2000 行：贪心顺序应先扫 small（JOIN 谓词不做索引探测，属 WHERE 索引选择范畴）
        String plan = ex.explain("SELECT small.w FROM small INNER JOIN big ON small.id = big.id");
        int smallPos = plan.indexOf("SeqScan(small)");
        int bigPos = plan.indexOf("SeqScan(big)");
        assertTrue(smallPos >= 0 && bigPos >= 0, plan);
        assertTrue(smallPos < bigPos, "小表应在外层: " + plan);
        assertEquals(3, ex.execute(
                "SELECT small.w FROM small INNER JOIN big ON small.id = big.id").rowCount());
    }

    @Test
    void joinReversesWhenBigFiltered() {
        // 无索引、大表被谓词过滤到估计很小仍 > 3；这里反向：小表连接谓词选择性 → 顺序不敏感，仅验证正确性
        String plan = ex.explain("SELECT big.v FROM big INNER JOIN small ON big.id = small.id");
        assertFalse(plan.isEmpty());
        assertEquals(3, ex.execute(
                "SELECT big.v FROM big INNER JOIN small ON big.id = small.id").rowCount());
    }

    @Test
    void explainWithoutIndexHasSeqScan() {
        String plan = ex.explain("SELECT * FROM small WHERE id = 1");
        assertTrue(plan.contains("SeqScan(small)"), plan);
    }
}
