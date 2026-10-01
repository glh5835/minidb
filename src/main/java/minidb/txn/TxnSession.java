package minidb.txn;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.BufferPool;
import minidb.storage.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 事务会话：锁列表、undo 栈、新建页追踪。
 * steal/no-force 协议下未提交脏页可被淘汰（经 FlushHook 保证 WAL 先写），
 * 仅"事务内新建的页"在提交时连同其前驱页强制落盘（页创建与链改动不入日志）。
 */
public final class TxnSession {
    public enum Isolation { READ_COMMITTED, REPEATABLE_READ }

    public final long txnId;
    public final Isolation isolation;
    public final boolean autoCommit;

    public final LockManager locks;
    public final List<String> heldLocks = new ArrayList<>();
    public final List<Runnable> undoActions = new ArrayList<>();
    public final Set<Integer> pinnedPages = new LinkedHashSet<>();
    /** 本事务新建的数据页（页创建不入 WAL，提交时 mini-force 落盘）。 */
    public final java.util.Map<Integer, Integer> newPages = new java.util.LinkedHashMap<>();
    public final BufferPool pool;
    public final boolean readOnly;
    public boolean finished;

    public TxnSession(long txnId, Isolation isolation, boolean autoCommit,
                      LockManager locks, BufferPool pool) {
        this(txnId, isolation, autoCommit, locks, pool, false);
    }

    public TxnSession(long txnId, Isolation isolation, boolean autoCommit,
                      LockManager locks, BufferPool pool, boolean readOnly) {
        this.txnId = txnId;
        this.isolation = isolation;
        this.autoCommit = autoCommit;
        this.locks = locks;
        this.pool = pool;
        this.readOnly = readOnly;
    }

    /** 行锁键 */
    public static String lockKey(String table, Rid rid) {
        return table + "/" + rid.pageId() + "/" + rid.slot();
    }

    /** 加行锁；READ COMMITTED 且 immediateRelease 时读完即放。返回是否本次新获取。 */
    public boolean lockRow(String table, Rid rid, boolean immediateRelease) {
        String key = lockKey(table, rid);
        try {
            locks.acquire(txnId, key, LockManager.Mode.X);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiniDbException(MiniDbException.Code.LOCK, "加锁被中断: " + key, e);
        }
        boolean isNew = !heldLocks.contains(key);
        if (isNew) heldLocks.add(key);
        if (immediateRelease && isolation == TxnSession.Isolation.READ_COMMITTED) {
            locks.release(txnId, key);
            heldLocks.remove(key);
            return isNew;
        }
        return isNew;
    }

    /** 立即释放一把行锁（UPDATE/DELETE 扫描中未命中 WHERE 的行）。 */
    public void unlockRow(String table, Rid rid) {
        String key = lockKey(table, rid);
        locks.release(txnId, key);
        heldLocks.remove(key);
    }

    /** 追踪事务内新建的数据页（提交时与其前驱页一起强制落盘）。 */
    public void trackNewPage(int pageId, int prevPageId) {
        newPages.putIfAbsent(pageId, prevPageId);
    }

    /** 兼容保留：steal 协议下不再 pin 修改页（no-op）。 */
    public void pinPage(int pageId) {
    }

    public void unpinAll(boolean dirty) {
        for (int pid : pinnedPages) pool.unpin(pid, dirty);
        pinnedPages.clear();
    }
}
