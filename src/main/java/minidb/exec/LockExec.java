package minidb.exec;

import minidb.common.Rid;
import minidb.txn.TxnSession;
import minidb.storage.Table;

import java.util.List;

/** 读锁算子：给扫描到的行加 S 语义行锁（实现用 X 锁 + RC 立即放/RR 持有）。 */
final class LockExec implements ExecOp {
    private final ExecOp child;
    private final TxnSession session;
    private final String table;

    LockExec(ExecOp child, TxnSession session, String table) {
        this.child = child;
        this.session = session;
        this.table = table;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] r = child.next();
        if (r == null) return null;
        Rid rid = child instanceof ScanExec se ? se.currentRid()
                : child instanceof IndexScanExec ix ? ix.currentRid() : null;
        if (rid != null) session.lockRow(table, rid, true); // RC 立即放；RR 持有到提交
        return r;
    }

    @Override
    public List<String> columns() {
        return child.columns();
    }

    @Override
    public ExecOp child() {
        return child;
    }

    @Override
    public List<ExecOp> children() {
        return List.of(child);
    }

    @Override
    public String describe() {
        return "RowLock(" + table + ")";
    }

    @Override
    public void close() {
        child.close();
    }
}
