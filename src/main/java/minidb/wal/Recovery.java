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

    public static void recover(Database db, List<WalLog.Rec> records) {
        // pass 1：分析
        Map<Long, List<WalLog.Rec>> byTxn = new HashMap<>();
        Set<Long> committed = new LinkedHashSet<>();
        for (WalLog.Rec r : records) {
            if (r.type() == WalLog.COMMIT) committed.add(r.txnId());
            if (r.type() == WalLog.INSERT || r.type() == WalLog.DELETE || r.type() == WalLog.UPDATE)
                byTxn.computeIfAbsent(r.txnId(), k -> new ArrayList<>()).add(r);
        }
        // pass 2：redo（LSN 序条件重放全部 DML，含未提交）
        for (WalLog.Rec r : records) redoRec(db, r);
        // pass 3：undo（未提交事务，逆序）
        for (Long txn : byTxn.keySet()) {
            if (committed.contains(txn)) continue;
            List<WalLog.Rec> ops = byTxn.get(txn);
            for (int i = ops.size() - 1; i >= 0; i--) {
                undoRec(db, ops.get(i));
            }
        }
    }

    /** 单条 DML 的条件 redo（幂等：磁盘状态已包含该记录效果则跳过）。 */
    static void redoRec(Database db, WalLog.Rec r) {
        Table t;
        try {
            t = db.getTable(r.table());
        } catch (MiniDbException e) {
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
                }
            }
            case WalLog.DELETE -> {
                Object[] before = decode(codec, r.before());
                if (rowMatches(t, rid, before)) {
                    t.delete(rid);
                    indexDelete(db, r.table(), before);
                }
            }
            case WalLog.UPDATE -> {
                Object[] before = decode(codec, r.before());
                Object[] after = decode(codec, r.after());
                if (rowMatches(t, rid, before)) {
                    t.update(rid, after);
                    indexDelete(db, r.table(), before);
                    indexInsert(db, r.table(), rid, after);
                }
            }
            default -> { /* BEGIN/COMMIT/ABORT 无操作 */ }
        }
    }

    /** 单条 DML 的条件 undo。 */
    static void undoRec(Database db, WalLog.Rec r) {
        try {
            Table t = db.getTable(r.table());
        } catch (MiniDbException e) {
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
                }
            }
            case WalLog.DELETE -> {
                Object[] before = decode(codec, r.before());
                if (!rowExists(t, rid)) {
                    t.restoreAt(rid, before);
                    indexInsert(db, r.table(), rid, before);
                }
            }
            case WalLog.UPDATE -> {
                Object[] after = decode(codec, r.after());
                Object[] before = decode(codec, r.before());
                if (rowMatches(t, rid, after)) {
                    Rid newRid = t.update(rid, before);
                    indexDelete(db, r.table(), after);
                    indexInsert(db, r.table(), newRid, before);
                }
            }
            default -> { /* BEGIN/COMMIT/ABORT 无操作 */ }
        }
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
