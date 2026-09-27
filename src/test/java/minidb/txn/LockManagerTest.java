package minidb.txn;

import minidb.common.MiniDbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 锁管理器：S/X 兼容、等待唤醒、死锁检测（等待图）、故障注入 */
class LockManagerTest {

    @Test
    void sLocksAreCompatible() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "t/1/0", LockManager.Mode.S);
        lm.acquire(2, "t/1/0", LockManager.Mode.S);
        assertEquals(2, lm.holdersOf("t/1/0").size());
    }

    @Test
    void xLockIsExclusive() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "k", LockManager.Mode.X);
        AtomicBoolean got = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                lm.acquire(2, "k", LockManager.Mode.X);
                got.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(120);
        assertFalse(got.get(), "第二个 X 应在等待");
        lm.release(1, "k");
        t.join(1000);
        assertTrue(got.get());
    }

    @Test
    void sBlocksX() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "k", LockManager.Mode.S);
        AtomicBoolean got = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                lm.acquire(2, "k", LockManager.Mode.X);
                got.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(100);
        assertFalse(got.get());
        lm.release(1, "k");
        t.join(1000);
        assertTrue(got.get());
    }

    @Test
    void releaseAllWakesWaiters() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "a", LockManager.Mode.X);
        lm.acquire(1, "b", LockManager.Mode.X);
        AtomicBoolean got = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                lm.acquire(2, "a", LockManager.Mode.X);
                got.set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        Thread.sleep(80);
        assertFalse(got.get());
        lm.releaseAll(1, List.of("a", "b"));
        t.join(1000);
        assertTrue(got.get());
    }

    @Test
    void lockUpgradeSameTxn() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "k", LockManager.Mode.S);
        lm.acquire(1, "k", LockManager.Mode.X); // 同事务升级
        assertEquals(1, lm.holdersOf("k").size());
    }

    @Test
    void deadlockDetectedAndVictimIsRequester() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "a", LockManager.Mode.X);
        lm.acquire(2, "b", LockManager.Mode.X);
        CountDownLatch t2HoldB = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    Thread.sleep(100);
                    lm.acquire(1, "b", LockManager.Mode.X); // T1 等 T2
                } catch (Exception ignored) {
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    t2HoldB.countDown();
                    Thread.sleep(200);
                    lm.acquire(2, "a", LockManager.Mode.X); // T2 等 T1 → 成环
                } catch (MiniDbException e) {
                    assertEquals(MiniDbException.Code.DEADLOCK, e.code);
                    lm.releaseAll(2, List.of("a", "b")); // 回滚释放锁，让 T1 通过
                } catch (Exception ignored) {
                }
            });
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadlockVictimGetsException() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "a", LockManager.Mode.X);
        lm.acquire(2, "b", LockManager.Mode.X);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    Thread.sleep(100);
                    lm.acquire(1, "b", LockManager.Mode.X);
                    lm.release(1, "b");
                } catch (Exception ignored) {
                }
            });
            Future<Boolean> f2 = pool.submit(() -> {
                try {
                    Thread.sleep(150);
                    lm.acquire(2, "a", LockManager.Mode.X);
                    return false;
                } catch (MiniDbException e) {
                    lm.releaseAll(2, List.of("a", "b")); // 回滚释放，T1 可获得 b
                    return e.code == MiniDbException.Code.DEADLOCK;
                }
            });
            f1.get(5, TimeUnit.SECONDS);
            assertTrue(f2.get(5, TimeUnit.SECONDS), "T2 应收到死锁异常");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void threeTxnCycleDetected() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "a", LockManager.Mode.X);
        lm.acquire(2, "b", LockManager.Mode.X);
        lm.acquire(3, "c", LockManager.Mode.X);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            pool.submit(() -> {
                try {
                    Thread.sleep(80);
                    lm.acquire(1, "b", LockManager.Mode.X); // 1→2
                } catch (Exception ignored) {
                }
            });
            pool.submit(() -> {
                try {
                    Thread.sleep(120);
                    lm.acquire(2, "c", LockManager.Mode.X); // 2→3
                } catch (Exception ignored) {
                }
            });
            Future<Boolean> f3 = pool.submit(() -> {
                try {
                    Thread.sleep(160);
                    lm.acquire(3, "a", LockManager.Mode.X); // 3→1 → 成环
                    return false;
                } catch (MiniDbException e) {
                    return e.code == MiniDbException.Code.DEADLOCK;
                }
            });
            assertTrue(f3.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void noDeadlockFalsePositive() throws Exception {
        LockManager lm = new LockManager();
        lm.acquire(1, "a", LockManager.Mode.X);
        // 事务 2 等 a；事务 1 释放后 2 获得；无环不应报死锁
        Thread t = new Thread(() -> {
            try {
                lm.acquire(2, "a", LockManager.Mode.X);
            } catch (Exception e) {
                fail("不应死锁: " + e.getMessage());
            }
        });
        t.start();
        Thread.sleep(80);
        lm.release(1, "a");
        t.join(1000);
        assertEquals(1, lm.holdersOf("a").size());
        assertEquals(2L, lm.holdersOf("a").iterator().next());
    }

    @Test
    void releaseUnknownKeyNoop() {
        LockManager lm = new LockManager();
        assertDoesNotThrow(() -> lm.release(1, "nope"));
        assertDoesNotThrow(() -> lm.releaseAll(1, List.of("nope")));
    }

    @Test
    @Timeout(10)
    void manyTxnsContentionCompletes() throws Exception {
        LockManager lm = new LockManager();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                final long id = i + 1;
                pool.submit(() -> {
                    for (int j = 0; j < 100; j++) {
                        lm.acquire(id, "hot", LockManager.Mode.X);
                        lm.release(id, "hot");
                    }
                    return null;
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS));
        }
    }
}
