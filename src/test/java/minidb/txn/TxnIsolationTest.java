package minidb.txn;

import minidb.common.MiniDbException;
import minidb.exec.Executor;
import minidb.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 事务与隔离级别：原子性、READ COMMITTED / REPEATABLE READ、死锁回滚 */
class TxnIsolationTest {
    @TempDir
    Path dir;

    private Database db;
    private Executor ex;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("t.db"), 256);
        ex = new Executor(db);
        ex.execute("CREATE TABLE acc (id INT, bal INT)");
        for (int i = 1; i <= 10; i++)
            ex.execute("INSERT INTO acc VALUES (" + i + ", 1000)");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private int bal(int id) {
        return (Integer) ex.execute("SELECT bal FROM acc WHERE id = " + id).rows().get(0)[0];
    }

    private int count(String where) {
        String sql = "SELECT COUNT(*) FROM acc" + (where.isEmpty() ? "" : " WHERE " + where);
        return (Integer) ex.execute(sql).rows().get(0)[0];
    }

    @Test
    void explicitCommitMakesChangesVisible() {
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE acc SET bal = 500 WHERE id = 1", s);
        ex.commit(s);
        assertEquals(500, bal(1));
    }

    @Test
    void rollbackUndoesChanges() {
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE acc SET bal = 1 WHERE id = 1", s);
        ex.execute("DELETE FROM acc WHERE id = 2", s);
        ex.execute("INSERT INTO acc VALUES (99, 99)", s);
        ex.rollback(s);
        assertEquals(1000, bal(1));
        assertEquals(1000, bal(2));
        assertEquals(0, count("id = 99"));
        assertEquals(10, count(""));
    }

    @Test
    void uncommittedChangesInvisibleToOthers() throws Exception {
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE acc SET bal = 7 WHERE id = 1", s);
        // 锁系统下：未提交的 X 锁会让其他会话的读阻塞（而不是读到脏数据）
        AtomicBoolean readerDone = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                bal(1); // 会阻塞
                readerDone.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(150);
        assertFalse(readerDone.get(), "读者应被未提交事务的 X 锁阻塞");
        ex.commit(s);
        t.join(3000);
        assertTrue(readerDone.get());
        assertEquals(7, bal(1)); // 提交后读到新值
    }

    @Test
    void readCommittedReadsLatestCommitted() throws Exception {
        TxnSession reader = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        int first = (Integer) ex.execute("SELECT bal FROM acc WHERE id = 1", reader).rows().get(0)[0];
        assertEquals(1000, first);
        // 其他事务提交新值
        TxnSession writer = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE acc SET bal = 555 WHERE id = 1", writer);
        ex.commit(writer);
        // RC：同事务再读 → 读到最新提交
        int second = (Integer) ex.execute("SELECT bal FROM acc WHERE id = 1", reader).rows().get(0)[0];
        assertEquals(555, second);
        ex.commit(reader);
    }

    @Test
    void repeatableReadHoldsSnapshotValue() throws Exception {
        // RR 下 X 锁互斥：写者等待读者事务结束（读者 S 锁持有到 commit）
        TxnSession reader = ex.begin(TxnSession.Isolation.REPEATABLE_READ);
        int first = (Integer) ex.execute("SELECT bal FROM acc WHERE id = 1", reader).rows().get(0)[0];
        assertEquals(1000, first);
        TxnSession writer = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        final int[] writerDone = {0};
        Thread t = new Thread(() -> {
            try {
                ex.execute("UPDATE acc SET bal = 555 WHERE id = 1", writer);
                ex.commit(writer);
                writerDone[0] = 1;
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(150);
        assertEquals(0, writerDone[0], "写者应被读者 S 锁阻塞（RR 严格 2PL）");
        // 读者再读 → 仍是旧值（可重复读）
        int again = (Integer) ex.execute("SELECT bal FROM acc WHERE id = 1", reader).rows().get(0)[0];
        assertEquals(1000, again);
        ex.commit(reader);
        t.join(3000);
        assertEquals(1, writerDone[0]);
        assertEquals(555, bal(1));
    }

    @Test
    void concurrentUpdateBlocksThenSucceeds() throws Exception {
        TxnSession s1 = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE acc SET bal = 1 WHERE id = 1", s1);
        AtomicBoolean done = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            TxnSession s2 = ex.begin(TxnSession.Isolation.READ_COMMITTED);
            try {
                ex.execute("UPDATE acc SET bal = 2 WHERE id = 1", s2);
                ex.commit(s2);
                done.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(150);
        assertFalse(done.get(), "应被行锁阻塞");
        ex.commit(s1);
        t.join(3000);
        assertTrue(done.get());
        assertEquals(2, bal(1));
    }

    @Test
    void deadlockBetweenTwoUpdatersRollsBackOne() throws Exception {
        ex.execute("CREATE INDEX acc_id ON acc(id)"); // 索引点更新：只锁命中行
        AtomicBoolean aDeadlock = new AtomicBoolean(false);
        AtomicBoolean bDeadlock = new AtomicBoolean(false);
        TxnSession b = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        Thread tb = new Thread(() -> {
            try {
                Thread.sleep(250);
                ex.execute("UPDATE acc SET bal = bal - 1 WHERE id = 2", b); // b 持行2
                Thread.sleep(300);
                ex.execute("UPDATE acc SET bal = bal - 1 WHERE id = 1", b); // b 等 a 的行1 → 成环
                ex.commit(b);
            } catch (MiniDbException e) {
                if (e.code == MiniDbException.Code.DEADLOCK) {
                    bDeadlock.set(true);
                    ex.rollback(b);
                }
            } catch (Exception ignored) {
            }
        });
        tb.start();
        Thread.sleep(80);
        TxnSession a = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        try {
            ex.execute("UPDATE acc SET bal = bal - 1 WHERE id = 1", a); // a 持行1
            Thread.sleep(800); // 确保 b 已持行2 并等待行1
            ex.execute("UPDATE acc SET bal = bal - 1 WHERE id = 2", a); // a 等 b 的行2 → 检测成环
            ex.commit(a);
        } catch (MiniDbException e) {
            if (e.code == MiniDbException.Code.DEADLOCK) {
                aDeadlock.set(true);
                ex.rollback(a);
            }
        }
        tb.join(10_000);
        assertTrue(aDeadlock.get() || bDeadlock.get(), "应检测到死锁并回滚一方");
        int b1 = bal(1), b2 = bal(2);
        assertTrue((b1 == 999 && b2 == 999) || (b1 == 999 && b2 == 1000) || (b1 == 1000 && b2 == 999),
                "b1=" + b1 + " b2=" + b2);
    }

    @Test
    void rollbackAfterInsertRemovesRow() {
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("INSERT INTO acc VALUES (88, 1)", s);
        assertEquals(1, (Integer) ex.execute("SELECT COUNT(*) FROM acc WHERE id = 88", s).rows().get(0)[0]);
        ex.rollback(s);
        assertEquals(0, count("id = 88"));
    }

    @Test
    void autoCommitIsStatementLevel() {
        // 单条语句自带事务：失败即回滚（行数守恒）
        assertThrows(MiniDbException.class,
                () -> ex.execute("INSERT INTO acc VALUES (1, 'bad-type')"));
        assertEquals(10, count(""));
    }

    @Test
    @Timeout(60)
    void interleavedTransfersStayConsistent() throws Exception {
        ex.execute("CREATE INDEX acc_id ON acc(id)");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            final int seed = t;
            pool.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    int from = (seed * 50 + i) % 10 + 1;
                    int to = (from % 10) + 1;
                    TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
                    try {
                        ex.execute("UPDATE acc SET bal = bal - 10 WHERE id = " + from, s);
                        ex.execute("UPDATE acc SET bal = bal + 10 WHERE id = " + to, s);
                        ex.commit(s);
                    } catch (MiniDbException e) {
                        ex.rollback(s);
                    }
                }
                return null;
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(55, TimeUnit.SECONDS));
        List<Object[]> rows = ex.execute("SELECT id, bal FROM acc ORDER BY id").rows();
        int total = 0;
        for (Object[] r : rows) total += (Integer) r[1];
        assertEquals(10_000, total, "转账后总额必须守恒");
    }
}
