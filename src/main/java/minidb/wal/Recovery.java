package minidb.wal;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.Database;
import minidb.storage.RowCodec;
import minidb.storage.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 崩溃恢复：redo + undo（steal + no-force，简化 ARIES）。
 *
 * 协议：DML 记录先于数据页落盘（日志临界区 + FlushHook 的 syncUpTo(pageLsn)）；
 * commit = append COMMIT + sync（no-force，数据页交给淘汰器）；事务新建页在提交时
 * 连同前驱页 mini-force（页创建与链改动不入日志）。迁移式 UPDATE 改记
 * DELETE(旧RID)+INSERT(新RID) 两条日志，重放与撤销都以稳定 RID 为锚。
 *
 * 恢复三步：
 *  1. 分析：按 txnId 分组，收集已提交事务集合；
 *  2. redo：LSN 序条件幂等重放全部 DML（含未提交——undo 随后撤销），行镜像一致才跳过；
 *  3. undo：对无 COMMIT 的事务按事务逆序、事务内 LSN 逆序执行条件 undo。
 * 条件应用（幂等）：镜像与当前行内容一致才操作，恢复可安全重入。
 */
public final class Recovery {
    private Recovery() {}

    /**
     * 恢复过程报告（Studio WAL/恢复实验用）：真实记录每个 redo/undo 决策与结果。
     * 只在显式传入收集时产生数据；常规恢复传 null，零额外开销。
     */
    public static final class RecoveryReport {
        /** 一条恢复动作：phase=ANALYZE/REDO/UNDO；applied=是否真实应用到数据页。 */
        public record Entry(long lsn, long txnId, String phase, String action, String table,
                            int pageId, int slot, boolean applied, String detail) {}

        private final List<Entry> entries = new ArrayList<>();
        private final Set<Long> committed = new LinkedHashSet<>();
        private final Set<Long> pending = new LinkedHashSet<>();

        public List<Entry> entries() { return entries; }
        public Set<Long> committedTxns() { return committed; }
        public Set<Long> pendingTxns() { return pending; }

        void analyzeCommit(long txnId) { committed.add(txnId); }
        void analyzeDml(long txnId) {
            if (!committed.contains(txnId)) pending.add(txnId);
        }

        void add(long lsn, long txnId, String phase, String action, String table,
                 int pageId, int slot, boolean applied, String detail) {
            entries.add(new Entry(lsn, txnId, phase, action, table, pageId, slot, applied, detail));
        }
    }

    /** 常规恢复（Database 打开时自动调用）；返回真实恢复报告。 */
    public static RecoveryReport recover(Database db, List<WalLog.Rec> records) {
        return recover(db, records, new RecoveryReport());
    }

    /** 指定报告容器恢复；report 传 null 时行为与以前完全一致（不收集）。 */
    public static RecoveryReport recover(Database db, List<WalLog.Rec> records, RecoveryReport report) {
        // pass 1：分析
        Map<Long, List<WalLog.Rec>> byTxn = new HashMap<>();
        Set<Long> committed = new LinkedHashSet<>();
        for (WalLog.Rec r : records) {
            if (r.type() == WalLog.COMMIT) {
                committed.add(r.txnId());
                if (report != null) {
                    report.analyzeCommit(r.txnId());
                    report.add(r.lsn(), r.txnId(), "ANALYZE", "COMMIT", "", -1, -1, true,
                            "事务已提交，redo 保留");
                }
            }
            if (r.type() == WalLog.INSERT || r.type() == WalLog.DELETE || r.type() == WalLog.UPDATE) {
                byTxn.computeIfAbsent(r.txnId(), k -> new ArrayList<>()).add(r);
            }
        }
        if (report != null) {
            for (Long txn : byTxn.keySet())
                if (!committed.contains(txn)) {
                    report.pendingTxns().add(txn);
                    report.add(-1, txn, "ANALYZE", "PENDING", "", -1, -1, true,
                            "事务未提交，undo 阶段撤销");
                }
        }
        // pass 2：redo（LSN 序条件重放全部 DML，含未提交）
        for (WalLog.Rec r : records) redoRec(db, r, report);
        // pass 3：undo（未提交事务，逆序）
        for (Long txn : byTxn.keySet()) {
            if (committed.contains(txn)) continue;
            List<WalLog.Rec> ops = byTxn.get(txn);
            for (int i = ops.size() - 1; i >= 0; i--) {
                undoRec(db, ops.get(i), report);
            }
        }
        return report;
    }

    /** 单条 DML 的条件 redo（幂等：磁盘状态已包含该记录效果则跳过）。 */
    static void redoRec(Database db, WalLog.Rec r, RecoveryReport report) {
        if (r.type() != WalLog.INSERT && r.type() != WalLog.DELETE && r.type() != WalLog.UPDATE) {
            if (report != null)
                report.add(r.lsn(), r.txnId(), "REDO", name(r.type()), r.table(),
                        r.pageId(), r.slot(), false, "非 DML 记录，无需操作");
            return;
        }
        Table t;
        try {
            t = db.getTable(r.table());
        } catch (MiniDbException e) {
            if (report != null)
                report.add(r.lsn(), r.txnId(), "REDO", name(r.type()), r.table(),
                        r.pageId(), r.slot(), false, "表已删除，跳过");
            return; // 表在日志后被删除，忽略
        }
        RowCodec codec = new RowCodec(t.schema());
        Rid rid = new Rid(r.pageId(), r.slot());
        switch (r.type()) {
            case WalLog.INSERT -> {
                Object[] after = decode(codec, r.after());
                if (!rowExists(t, rid)) {
                    t.restoreAt(rid, after);
                    indexInsert(db, r.table(), rid, after);
                    if (report != null) report.add(r.lsn(), r.txnId(), "REDO", "INSERT",
                            r.table(), r.pageId(), r.slot(), true, "重放插入 + 索引回补");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "REDO", "INSERT", r.table(),
                            r.pageId(), r.slot(), false, "行已存在，幂等跳过");
                }
            }
            case WalLog.DELETE -> {
                Object[] before = decode(codec, r.before());
                if (rowMatches(t, rid, before)) {
                    t.delete(rid);
                    indexDelete(db, r.table(), before);
                    if (report != null) report.add(r.lsn(), r.txnId(), "REDO", "DELETE",
                            r.table(), r.pageId(), r.slot(), true, "重放删除 + 索引维护");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "REDO", "DELETE", r.table(),
                            r.pageId(), r.slot(), false, "行内容不一致，幂等跳过");
                }
            }
            case WalLog.UPDATE -> {
                Object[] before = decode(codec, r.before());
                Object[] after = decode(codec, r.after());
                if (rowMatches(t, rid, before)) {
                    t.update(rid, after);
                    indexDelete(db, r.table(), before);
                    indexInsert(db, r.table(), rid, after);
                    if (report != null) report.add(r.lsn(), r.txnId(), "REDO", "UPDATE",
                            r.table(), r.pageId(), r.slot(), true, "重放更新 + 索引维护");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "REDO", "UPDATE", r.table(),
                            r.pageId(), r.slot(), false, "行内容不一致，幂等跳过");
                }
            }
            default -> { /* BEGIN/COMMIT/ABORT 无操作 */ }
        }
    }

    /** 单条 DML 的条件 undo。 */
    static void undoRec(Database db, WalLog.Rec r, RecoveryReport report) {
        if (r.type() != WalLog.INSERT && r.type() != WalLog.DELETE && r.type() != WalLog.UPDATE) {
            if (report != null)
                report.add(r.lsn(), r.txnId(), "UNDO", name(r.type()), r.table(),
                        r.pageId(), r.slot(), false, "非 DML 记录，无需操作");
            return;
        }
        try {
            Table t = db.getTable(r.table());
        } catch (MiniDbException e) {
            if (report != null)
                report.add(r.lsn(), r.txnId(), "UNDO", name(r.type()), r.table(),
                        r.pageId(), r.slot(), false, "表已删除，跳过");
            return; // 表在日志后被删除，忽略
        }
        Table t = db.getTable(r.table());
        RowCodec codec = new RowCodec(t.schema());
        Rid rid = new Rid(r.pageId(), r.slot());
        switch (r.type()) {
            case WalLog.INSERT -> {
                Object[] after = decode(codec, r.after());
                if (rowMatches(t, rid, after)) {
                    t.delete(rid);
                    indexDelete(db, r.table(), after);
                    if (report != null) report.add(r.lsn(), r.txnId(), "UNDO", "INSERT",
                            r.table(), r.pageId(), r.slot(), true, "撤销插入（删行 + 索引清理）");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "UNDO", "INSERT", r.table(),
                            r.pageId(), r.slot(), false, "行不存在，幂等跳过");
                }
            }
            case WalLog.DELETE -> {
                Object[] before = decode(codec, r.before());
                if (!rowExists(t, rid)) {
                    t.restoreAt(rid, before);
                    indexInsert(db, r.table(), rid, before);
                    if (report != null) report.add(r.lsn(), r.txnId(), "UNDO", "DELETE",
                            r.table(), r.pageId(), r.slot(), true, "撤销删除（原位恢复 + 索引回补）");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "UNDO", "DELETE", r.table(),
                            r.pageId(), r.slot(), false, "行已存在，幂等跳过");
                }
            }
            case WalLog.UPDATE -> {
                Object[] after = decode(codec, r.after());
                Object[] before = decode(codec, r.before());
                if (rowMatches(t, rid, after)) {
                    Rid newRid = t.update(rid, before);
                    indexDelete(db, r.table(), after);
                    indexInsert(db, r.table(), newRid, before);
                    if (report != null) report.add(r.lsn(), r.txnId(), "UNDO", "UPDATE",
                            r.table(), r.pageId(), r.slot(), true,
                            "撤销更新（恢复旧值，RID " + rid + " → " + newRid + "）");
                } else if (report != null) {
                    report.add(r.lsn(), r.txnId(), "UNDO", "UPDATE", r.table(),
                            r.pageId(), r.slot(), false, "行内容不一致，幂等跳过");
                }
            }
            default -> { /* BEGIN/COMMIT/ABORT 无操作 */ }
        }
    }

    private static String name(byte type) {
        return switch (type) {
            case WalLog.BEGIN -> "BEGIN";
            case WalLog.INSERT -> "INSERT";
            case WalLog.DELETE -> "DELETE";
            case WalLog.UPDATE -> "UPDATE";
            case WalLog.COMMIT -> "COMMIT";
            case WalLog.ABORT -> "ABORT";
            default -> "TYPE" + type;
        };
    }

    static Object[] decode(RowCodec codec, byte[] bytes) {
        return codec.decode(bytes, 0, bytes.length);
    }

    static boolean rowExists(Table t, Rid rid) {
        try {
            t.get(rid);
            return true;
        } catch (MiniDbException e) {
            return false;
        }
    }

    static boolean rowMatches(Table t, Rid rid, Object[] expected) {
        try {
            return Arrays.equals(t.get(rid), expected);
        } catch (MiniDbException e) {
            return false;
        }
    }

    static void indexInsert(Database db, String table, Rid rid, Object[] row) {
        try {
            Table t = db.getTable(table);
            for (Database.IndexEntry ie : db.indexesFor(table)) {
                int ci = t.schema().columnIndex(ie.meta().column());
                minidb.storage.IndexKeys.insert(ie.tree(), ie.meta().keyType(), row[ci], rid);
            }
        } catch (MiniDbException ignored) {
            // 表已删除
        }
    }

    static void indexDelete(Database db, String table, Object[] row) {
        try {
            Table t = db.getTable(table);
            for (Database.IndexEntry ie : db.indexesFor(table)) {
                int ci = t.schema().columnIndex(ie.meta().column());
                minidb.storage.IndexKeys.delete(ie.tree(), ie.meta().keyType(), row[ci]);
            }
        } catch (MiniDbException ignored) {
            // 表已删除
        }
    }
}
