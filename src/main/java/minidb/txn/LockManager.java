package minidb.txn;

import minidb.common.MiniDbException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行级锁管理器 + 严格 2PL + 等待图死锁检测。
 *
 * 锁键 = "table/页/槽"。兼容矩阵：S-S 兼容；含 X 即不兼容。
 * 请求不兼容锁时进入 FIFO 等待队列，并在等待图中记录 等待者→持锁者 边；
 * 加锁前 DFS 找环，成环即抛 DeadlockException（当前请求者作为牺牲者）。
 */
public final class LockManager {
    public enum Mode { S, X }

    private static final class LockEntry {
        final Set<Long> holders = new HashSet<>();
        Mode mode;
        final Deque<Waiter> queue = new ArrayDeque<>();
    }

    private static final class Waiter {
        final long txnId;
        final Mode mode;
        boolean granted;

        Waiter(long txnId, Mode mode) {
            this.txnId = txnId;
            this.mode = mode;
        }
    }

    private final Map<String, LockEntry> locks = new HashMap<>();

    /** 加锁；需要等待时阻塞；检测到死锁抛 DeadlockException（自己为牺牲者）。 */
    public synchronized void acquire(long txnId, String key, Mode mode) throws InterruptedException {
        LockEntry e = locks.computeIfAbsent(key, k -> new LockEntry());
        if (canGrant(e, txnId, mode)) {
            grant(e, txnId, mode);
            return;
        }
        Waiter w = new Waiter(txnId, mode);
        e.queue.addLast(w);
        try {
            detectDeadlock(txnId);
        } catch (MiniDbException de) {
            e.queue.remove(w);
            throw de;
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (!w.granted) {
            wait(5);
            if (!w.granted && System.currentTimeMillis() > deadline) {
                e.queue.remove(w);
                throw new MiniDbException(MiniDbException.Code.DEADLOCK,
                        "事务 " + txnId + " 等锁超时，回滚");
            }
        }
    }

    /** 释放一把锁；队列头等待者被授予。 */
    public synchronized void release(long txnId, String key) {
        LockEntry e = locks.get(key);
        if (e == null) return;
        e.holders.remove(txnId);
        if (e.holders.isEmpty()) {
            e.mode = null;
            // 直接把队首等待者授予进 holders（避免与新的请求者竞态）
            Waiter w = e.queue.pollFirst();
            if (w != null) {
                e.mode = w.mode;
                e.holders.add(w.txnId);
                w.granted = true;
            }
            notifyAll();
            if (e.holders.isEmpty() && e.queue.isEmpty()) locks.remove(key, e);
        }
    }

    private boolean canGrant(LockEntry e, long txnId, Mode mode) {
        if (e.holders.isEmpty()) return true;
        if (e.holders.size() == 1 && e.holders.contains(txnId)) return true; // 锁升级
        return e.mode == Mode.S && mode == Mode.S;
    }

    private void grant(LockEntry e, long txnId, Mode mode) {
        e.mode = mode;
        e.holders.add(txnId);
    }

    /** 事务结束时释放其全部锁。 */
    public synchronized void releaseAll(long txnId, Iterable<String> keys) {
        for (String k : keys) release(txnId, k);
        notifyAll();
    }

    /**
     * 死锁检测：实时重建等待图（每个排队 waiter → 该锁的当前持有者），
     * 避免增量维护的陈旧边漏检；从 victim 出发 DFS 找回到 victim 的路径。
     */
    private void detectDeadlock(long victim) {
        Map<Long, Set<Long>> graph = new HashMap<>();
        for (LockEntry e : locks.values()) {
            for (Waiter w : e.queue) {
                Set<Long> nexts = graph.computeIfAbsent(w.txnId, k -> new HashSet<>());
                nexts.addAll(e.holders);
                nexts.remove(w.txnId);
            }
        }
        if (dfs(victim, new HashSet<>(), victim, graph))
            throw new MiniDbException(MiniDbException.Code.DEADLOCK,
                    "检测到死锁，回滚事务 " + victim);
    }

    private boolean dfs(long node, Set<Long> visited, long target, Map<Long, Set<Long>> graph) {
        Set<Long> nexts = graph.get(node);
        if (nexts == null) return false;
        for (long n : nexts) {
            if (n == target) return true;
            if (visited.add(n) && dfs(n, visited, target, graph)) return true;
        }
        return false;
    }

    /** 测试辅助：当前键的持锁事务 */
    public synchronized Set<Long> holdersOf(String key) {
        LockEntry e = locks.get(key);
        return e == null ? Set.of() : new HashSet<>(e.holders);
    }

    /** 一把锁的只读快照（Studio 锁等待图用）：持锁者 + 等待队列（FIFO 序）。 */
    public record LockSnapshot(String key, List<Long> holders, String mode, List<Long> waiting) {}

    /** 只读快照全部锁条目；不改任何状态。等待图 = 每个 waiting 事务 → 该锁 holders 的边。 */
    public synchronized List<LockSnapshot> snapshot() {
        List<LockSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, LockEntry> en : locks.entrySet()) {
            LockEntry e = en.getValue();
            List<Long> waiting = new ArrayList<>();
            for (Waiter w : e.queue) waiting.add(w.txnId);
            out.add(new LockSnapshot(en.getKey(), new ArrayList<>(e.holders),
                    e.mode == null ? null : e.mode.name(), waiting));
        }
        return out;
    }
}
