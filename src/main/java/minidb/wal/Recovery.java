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
 * 崩溃恢复：undo-only（no-steal + force-at-commit）。
 *
 * 协议：事务的 DML 先 append WAL；commit 时 wal.sync() → flush 事务 pin 的页 → append COMMIT + sync。
 * 因此：磁盘上存在某未提交事务的部分修改，当且仅当其日志完整（先于数据落盘）→ 条件 undo 即可；
 * 已提交事务的修改必然已全部落盘 → 无需 redo。
 * 恢复步骤：读日志 → 找出无 COMMIT 记录的事务 → 按事务逆序、事务内 LSN 逆序执行条件 undo → 截断日志。
 * 条件应用（幂等）：镜像与当前行内容一致才操作，恢复可安全重入。
 */
public final class Recovery {
    private Recovery() {}

    public static void recover(Database db, List<WalLog.Rec> records) {
        // 按 txn 分组
        Map<Long, List<WalLog.Rec>> byTxn = new HashMap<>();
        Set<Long> committed = new LinkedHashSet<>();
        for (WalLog.Rec r : records) {
            if (r.type() == WalLog.COMMIT) committed.add(r.txnId());
            if (r.type() == WalLog.INSERT || r.type() == WalLog.DELETE || r.type() == WalLog.UPDATE)
                byTxn.computeIfAbsent(r.txnId(), k -> new ArrayList<>()).add(r);
        }
        for (Long txn : byTxn.keySet()) {
            if (committed.contains(txn)) continue;
            List<WalLog.Rec> ops = byTxn.get(txn);
            for (int i = ops.size() - 1; i >= 0; i--) {
                undoRec(db, ops.get(i));
            }
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

    /** 条件 REDO（保留接口：当前协议为 force-at-commit，redo 通常为空操作）。 */
    public static void redoCommitted(Database db, List<WalLog.Rec> records) {
        Set<Long> committed = new LinkedHashSet<>();
        for (WalLog.Rec r : records) if (r.type() == WalLog.COMMIT) committed.add(r.txnId());
        for (WalLog.Rec r : records) {
            if (!committed.contains(r.txnId())) continue;
            Table t;
            try {
                t = db.getTable(r.table());
            } catch (MiniDbException e) {
                continue;
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
                default -> { /* UPDATE/DELETE 在 force-at-commit 下必已落盘 */ }
            }
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
