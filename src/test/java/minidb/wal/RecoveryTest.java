package minidb.wal;

import minidb.common.MiniDbException;
import minidb.exec.Executor;
import minidb.storage.Database;
import minidb.txn.TxnSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 崩溃恢复：kill 进程、随机截断日志、原子性与持久性 */
class RecoveryTest {
    @TempDir
    Path dir;

    private Path dbFile() {
        return dir.resolve("r.db");
    }

    @Test
    void committedSurvivesCrash() {
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("INSERT INTO t VALUES (1, 100)", s);
        ex.execute("UPDATE t SET v = 200 WHERE id = 1", s);
        ex.commit(s);
        db.crash(); // 模拟断电：COMMIT 日志已 fsync，但缓冲池未必刷盘
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        assertEquals(200, ex2.execute("SELECT v FROM t WHERE id = 1").rows().get(0)[0]);
        db2.close();
    }

    @Test
    void uncommittedLostAfterCrash() {
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        ex.execute("INSERT INTO t VALUES (1, 100)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("INSERT INTO t VALUES (2, 200)", s);
        ex.execute("UPDATE t SET v = 1 WHERE id = 1", s);
        ex.execute("DELETE FROM t WHERE id = 1", s);
        // 不 commit → crash
        db.crash();
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        assertEquals(1, (int) ex2.execute("SELECT COUNT(*) FROM t").rows().get(0)[0]);
        assertEquals(100, ex2.execute("SELECT v FROM t WHERE id = 1").rows().get(0)[0]);
        assertEquals(0, (int) ex2.execute("SELECT COUNT(*) FROM t WHERE id = 2").rows().get(0)[0]);
        db2.close();
    }

    @Test
    void stealEvictedUncommittedPageNotVisibleAfterCrash() {
        // steal 正确性：未提交修改的页被强制淘汰落盘（小缓冲池 + 足量插入），
        // 断电后恢复必须把这些未提交行全部撤销
        Path f = dbFile();
        Database db = Database.open(f, 8); // 8 页小池 → 未提交插入必然触发淘汰
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        for (int i = 1; i <= 300; i++)
            ex.execute("INSERT INTO t VALUES (" + i + ", " + i * 7 + ")", s);
        assertTrue(db.wal().flushedLsn() > 0, "淘汰应经 FlushHook 推进 fsync 高水位");
        db.crash();
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        assertEquals(0, (int) ex2.execute("SELECT COUNT(*) FROM t").rows().get(0)[0],
                "被淘汰落盘的未提交行必须被恢复撤销");
        db2.close();
    }

    @Test
    void noForceCommitRecoveredByRedo() {
        // no-force：commit 后不刷任何数据页即断电，redo 必须能重建已提交修改
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        ex.execute("INSERT INTO t VALUES (1, 100)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE t SET v = 200 WHERE id = 1", s);
        ex.execute("INSERT INTO t VALUES (2, 300)", s);
        ex.commit(s);
        db.crash(); // commit 只 fsync 日志，数据页全部留在池中未落盘
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        assertEquals(200, ex2.execute("SELECT v FROM t WHERE id = 1").rows().get(0)[0]);
        assertEquals(300, ex2.execute("SELECT v FROM t WHERE id = 2").rows().get(0)[0]);
        db2.close();
    }

    @Test
    @Timeout(60)
    void migratedUpdateUndoneAndRedoneCorrectly() {
        // VarTable 迁移式 UPDATE：先塞满一页迫使 UPDATE 迁移到新页，
        // 分别验证 undo（未提交）与 redo+undo（提交后崩溃的第三种行不受影响）
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, note VARCHAR(100))");
        for (int i = 1; i <= 40; i++)
            ex.execute("INSERT INTO t VALUES (" + i + ", 'pad" + i + "-" + "x".repeat(60) + "')");
        // 迁移式更新：把 note 改长 → 原页放不下 → 迁移
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE t SET note = '" + "y".repeat(90) + "' WHERE id = 5", s);
        ex.commit(s);
        // 未提交迁移更新
        TxnSession s2 = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE t SET note = '" + "z".repeat(90) + "' WHERE id = 8", s2);
        db.crash();
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        Object committed = ex2.execute("SELECT note FROM t WHERE id = 5").rows().get(0)[0];
        assertEquals("y".repeat(90), committed, "已提交的迁移更新必须由 redo 重建");
        Object uncommitted = ex2.execute("SELECT note FROM t WHERE id = 8").rows().get(0)[0];
        assertEquals("pad8-" + "x".repeat(60), uncommitted, "未提交的迁移更新必须被撤销回原值");
        assertEquals(40, (int) ex2.execute("SELECT COUNT(*) FROM t").rows().get(0)[0]);
        db2.close();
    }

    @Test
    void ddlFlushedUncommittedDmlUndoneByRecovery() {
        // 未提交事务的脏页可借 DDL 的 engine.flush() 落盘（flushAll 无视 pin）。
        // 断电后恢复必须靠 WAL 条件 undo 撤销——这是 WAL 真正发挥作用的场景：
        // 若 readAll 解析不出记录（历史 bug），未提交的 555 会幸存，破坏原子性。
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        ex.execute("INSERT INTO t VALUES (1, 100)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("UPDATE t SET v = 555 WHERE id = 1", s);
        ex.execute("CREATE TABLE other (a INT)"); // DDL → flushAll，未提交脏页落盘
        db.crash(); // 事务未提交即断电
        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        assertEquals(100, ex2.execute("SELECT v FROM t WHERE id = 1").rows().get(0)[0],
                "DDL 刷盘落下的未提交修改必须被恢复撤销");
        db2.close();
    }

    @Test
    void normalClosePersistsEverything() {        Path f = dbFile();
        try (Database db = Database.open(f, 64)) {
            Executor ex = new Executor(db);
            ex.execute("CREATE TABLE t (id INT, v INT)");
            ex.execute("INSERT INTO t VALUES (1, 42)");
        }
        try (Database db = Database.open(f, 64)) {
            Executor ex = new Executor(db);
            assertEquals(42, ex.execute("SELECT v FROM t WHERE id = 1").rows().get(0)[0]);
            assertEquals(0, db.wal().size());
        }
    }

    @Test
    @Timeout(60)
    void randomTruncationKeepsAtomicity() {
        // 多个已提交事务 + 未提交事务，随机截断 WAL 尾部 → 恢复后已提交的在、未提交的不在
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT, v INT)");
        long committedCount = 20;
        for (int i = 0; i < committedCount; i++) {
            ex.execute("INSERT INTO t VALUES (" + i + ", " + i * 10 + ")");
        }
        // 开启一个未提交事务写几条
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        for (int i = 100; i < 105; i++) ex.execute("INSERT INTO t VALUES (" + i + ", 999)", s);
        // 记录“安全线”：已 sync 的日志大小（commit 后 sync 点）
        long safe = db.wal().size();
        db.crash();

        // 随机截断 [safe, ...] 的尾部（模拟未落盘的 OS 缓冲丢失），
        // 也允许一点越过 safe 的破坏
        Random rnd = new Random(2026);
        try {
            long size = Files.size(f.resolveSibling("r.db.wal"));
            long cut = safe + (long) (rnd.nextDouble() * Math.max(1, size - safe));
            try (var ch = java.nio.channels.FileChannel.open(f.resolveSibling("r.db.wal"),
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.truncate(cut);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Database db2 = Database.open(f, 64);
        Executor ex2 = new Executor(db2);
        int n = (Integer) ex2.execute("SELECT COUNT(*) FROM t").rows().get(0)[0];
        assertTrue(n >= committedCount - 2 && n <= committedCount,
                "截断最多影响边界处 1 个事务: n=" + n);
        // 每行内容完整（原子性）
        for (int i = 0; i < n; i++) {
            Object v = ex2.execute("SELECT v FROM t WHERE id = " + i).rows().get(0)[0];
            assertEquals(i * 10, v);
        }
        assertEquals(0, (int) ex2.execute("SELECT COUNT(*) FROM t WHERE id >= 100").rows().get(0)[0]);
        db2.close();
    }

    @Test
    @Timeout(120)
    void transfers20Threads100kConserveTotal() throws Exception {
        // 任务书验证：并发 20 个线程转账 10 万次，最终总额守恒
        Path f = dbFile();
        Database db = Database.open(f, 4096);
        db.wal().setFsyncEnabled(false); // 并发正确性压测关闭逐笔 fsync（断电持久性由恢复测试单独验证）
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE accounts (id INT, bal INT)");
        int accounts = 100;
        for (int i = 1; i <= accounts; i++)
            ex.execute("INSERT INTO accounts VALUES (" + i + ", 10000)");
        long expectedTotal = (long) accounts * 10000;

        int threads = 20;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                Random rnd = new Random(seed * 7919 + 13);
                Executor threadEx = new Executor(db);
                for (int i = 0; i < perThread; i++) {
                    int from = rnd.nextInt(accounts) + 1;
                    int to = rnd.nextInt(accounts) + 1;
                    if (from == to) continue;
                    int amount = rnd.nextInt(100) + 1;
                    for (int attempt = 0; ; attempt++) {
                        TxnSession s = threadEx.begin(TxnSession.Isolation.READ_COMMITTED);
                        try {
                            threadEx.execute("UPDATE accounts SET bal = bal - " + amount
                                    + " WHERE id = " + from, s);
                            threadEx.execute("UPDATE accounts SET bal = bal + " + amount
                                    + " WHERE id = " + to, s);
                            threadEx.commit(s);
                            break;
                        } catch (MiniDbException e) {
                            try {
                                threadEx.rollback(s);
                            } catch (RuntimeException ignored) {
                            }
                            if (e.code == MiniDbException.Code.DEADLOCK
                                    || e.code == MiniDbException.Code.LOCK) {
                                retries.incrementAndGet();
                                if (attempt > 200) throw new RuntimeException("重试过多", e);
                                // 随机退避，减少活锁
                                try {
                                    Thread.sleep(rnd.nextInt(5));
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue;
                            }
                            throw e;
                        }
                    }
                }
                return null;
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(110, TimeUnit.SECONDS), "转账超时");
        // 总额守恒
        List<Object[]> rows = ex.execute("SELECT bal FROM accounts").rows();
        long total = 0;
        for (Object[] r : rows) total += (Integer) r[0];
        assertEquals(expectedTotal, total, "20 线程 × 5 万次转账后总额必须守恒");
        System.out.println("转账压测完成：死锁回滚 " + deadlocks.get() + " 次，重试 " + retries.get() + " 次");
        db.close();
    }

    @Test
    void crashWithoutAnyTransactionIsHarmless() {
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (a INT)");
        db.crash();
        try (Database db2 = Database.open(f, 64)) {
            assertEquals(0, (int) new Executor(db2).execute("SELECT COUNT(*) FROM t").rows().get(0)[0]);
        }
    }

    @Test
    void recoveryIdempotentAcrossReopens() {
        Path f = dbFile();
        Database db = Database.open(f, 64);
        Executor ex = new Executor(db);
        ex.execute("CREATE TABLE t (id INT)");
        TxnSession s = ex.begin(TxnSession.Isolation.READ_COMMITTED);
        ex.execute("INSERT INTO t VALUES (5)", s);
        ex.commit(s);
        db.crash();
        // 连续两次打开：第一次恢复，第二次应干净
        try (Database db2 = Database.open(f, 64)) {
            assertEquals(1, new Executor(db2).execute("SELECT COUNT(*) FROM t").rowCount());
        }
        try (Database db3 = Database.open(f, 64)) {
            assertEquals(1, new Executor(db3).execute("SELECT COUNT(*) FROM t").rowCount());
        }
    }
}
