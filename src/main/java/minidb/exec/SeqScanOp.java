package minidb.exec;

import minidb.storage.Row;
import minidb.storage.Table;

import java.util.Iterator;

/** 顺序扫描算子：全表扫描。 */
public final class SeqScanOp implements Op {
    private final Table table;
    private Iterator<Row> it;

    public SeqScanOp(Table table) {
        this.table = table;
    }

    @Override
    public void open() {
        it = table.scan();
    }

    @Override
    public Row next() {
        return it != null && it.hasNext() ? it.next() : null;
    }

    @Override
    public void close() {
        it = null;
    }

    public Table table() { return table; }
}
