package minidb.exec;

import minidb.common.Rid;
import minidb.storage.Row;
import minidb.storage.Table;

import java.util.Iterator;
import java.util.List;

/** 顺序扫描。 */
final class ScanExec implements ExecOp {
    private final Table table;
    private final String alias;
    private Iterator<Row> it;
    private Rid currentRid;

    ScanExec(Table table, String alias) {
        this.table = table;
        this.alias = alias != null ? alias : table.schema().tableName();
    }

    @Override
    public void open() {
        it = table.scan();
        currentRid = null;
    }

    @Override
    public Object[] next() {
        if (it == null) throw new IllegalStateException("open() 未调用");
        if (!it.hasNext()) return null;
        Row r = it.next();
        currentRid = r.rid();
        return r.values();
    }

    /** 最近一次 next() 返回行的 RID（UPDATE/DELETE 用）。 */
    public Rid currentRid() {
        return currentRid;
    }

    @Override
    public List<String> columns() {
        return table.schema().columns().stream()
                .map(c -> alias + "." + c.name()).toList();
    }

    public Table table() { return table; }
    public String alias() { return alias; }

    @Override
    public String describe() {
        return "SeqScan(" + alias + ")";
    }

    @Override
    public void close() {
        it = null;
    }
}

/** 过滤。 */
final class FilterExec implements ExecOp {
    private final ExecOp child;
    private final Expressions.EvalNode cond;

    FilterExec(ExecOp child, Expressions.EvalNode cond) {
        this.child = child;
        this.cond = cond;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        while (true) {
            Object[] r = child.next();
            if (r == null) return null;
            if (Expressions.truthy(cond.eval(r))) return r;
        }
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
    public void close() {
        child.close();
    }
}

/** 投影（含表达式列与别名）。 */
final class ProjectExec implements ExecOp {
    private final ExecOp child;
    private final List<Expressions.EvalNode> exprs;
    private final List<String> outCols;

    ProjectExec(ExecOp child, List<Expressions.EvalNode> exprs, List<String> outCols) {
        this.child = child;
        this.exprs = exprs;
        this.outCols = outCols;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] r = child.next();
        if (r == null) return null;
        Object[] out = new Object[exprs.size()];
        for (int i = 0; i < out.length; i++) out[i] = exprs.get(i).eval(r);
        return out;
    }

    @Override
    public List<String> columns() {
        return outCols;
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
        return "Project(" + outCols.size() + " cols)";
    }

    @Override
    public void close() {
        child.close();
    }
}

/** 嵌套循环连接（INNER / LEFT）。 */
final class NestedLoopJoinExec implements ExecOp {
    private final ExecOp left;
    private final ExecOp right;
    private final boolean leftJoin;
    private final Expressions.EvalNode on; // 可为 null（笛卡尔积）
    private final List<String> cols;
    private Object[] leftRow;
    private boolean matched;

    NestedLoopJoinExec(ExecOp left, ExecOp right, boolean leftJoin, Expressions.EvalNode on) {
        this.left = left;
        this.right = right;
        this.leftJoin = leftJoin;
        this.on = on;
        this.cols = concat(left.columns(), right.columns());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Override
    public void open() {
        left.open();
        right.open();
        leftRow = null;
        matched = false;
    }

    @Override
    public Object[] next() {
        while (true) {
            if (leftRow == null) {
                leftRow = left.next();
                if (leftRow == null) return null;
                right.close();
                right.open(); // 右子树重开
                matched = false;
            }
            Object[] rr = right.next();
            if (rr == null) {
                // 左行耗尽右行
                Object[] emit = null;
                if (leftJoin && !matched) {
                    Object[] out = new Object[cols.size()];
                    System.arraycopy(leftRow, 0, out, 0, leftRow.length);
                    emit = out; // 右侧保持 null
                }
                leftRow = null;
                if (emit != null) return emit;
                continue;
            }
            Object[] combined = new Object[cols.size()];
            System.arraycopy(leftRow, 0, combined, 0, leftRow.length);
            System.arraycopy(rr, 0, combined, leftRow.length, rr.length);
            if (on == null || Expressions.truthy(on.eval(combined))) {
                matched = true;
                return combined;
            }
        }
    }

    @Override
    public List<String> columns() {
        return cols;
    }

    @Override
    public String describe() {
        return "NestedLoopJoin(" + leftJoin + ")";
    }

    @Override
    public List<ExecOp> children() {
        return List.of(left, right);
    }

    @Override
    public void close() {
        left.close();
        right.close();
    }
}

/** 排序（物化后内存排序，支持重开复用）。 */
final class SortExec implements ExecOp {
    record SortKey(Expressions.EvalNode node, boolean desc) {}

    private final ExecOp child;
    private final List<SortKey> keys;
    private java.util.List<Object[]> sorted;
    private int pos;

    SortExec(ExecOp child, List<SortKey> keys) {
        this.child = child;
        this.keys = keys;
    }

    @Override
    public void open() {
        // 物化（重开时复用缓存，数据不变）
        if (sorted == null) {
            child.open();
            sorted = new java.util.ArrayList<>();
            Object[] r;
            while ((r = child.next()) != null) sorted.add(r);
            child.close();
            java.util.Comparator<Object[]> cmp = (a, b) -> {
                for (SortKey k : keys) {
                    Object va = k.node().eval(a), vb = k.node().eval(b);
                    int c;
                    if (va == null && vb == null) c = 0;
                    else if (va == null) c = -1; // NULL 排最前
                    else if (vb == null) c = 1;
                    else c = Expressions.compare(va, vb);
                    if (c != 0) return k.desc() ? -c : c;
                }
                return 0;
            };
            sorted.sort(cmp);
        }
        pos = 0;
    }

    @Override
    public Object[] next() {
        if (sorted == null) throw new IllegalStateException("open() 未调用");
        return pos < sorted.size() ? sorted.get(pos++) : null;
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
        return "Sort(" + keys.size() + " keys)";
    }

    @Override
    public void close() {
        // 保留缓存供重开
    }
}

/** LIMIT / OFFSET。 */
final class LimitExec implements ExecOp {
    private final ExecOp child;
    private final long limit;
    private final long offset;
    private long skipped;
    private long emitted;

    LimitExec(ExecOp child, Long limit, Long offset) {
        this.child = child;
        this.limit = limit == null ? Long.MAX_VALUE : limit;
        this.offset = offset == null ? 0 : offset;
    }

    @Override
    public void open() {
        child.open();
        skipped = 0;
        emitted = 0;
    }

    @Override
    public Object[] next() {
        while (skipped < offset) {
            if (child.next() == null) return null;
            skipped++;
        }
        if (emitted >= limit) return null;
        Object[] r = child.next();
        if (r == null) return null;
        emitted++;
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
        return "Limit(" + limit + ", offset " + offset + ")";
    }

    @Override
    public void close() {
        child.close();
    }
}

/** DISTINCT（按整行哈希去重，保持首次出现顺序）。 */
final class DistinctExec implements ExecOp {
    private final ExecOp child;
    private java.util.List<Object[]> distinct;
    private java.util.Set<java.util.List<Object>> seen;
    private int pos;

    DistinctExec(ExecOp child) {
        this.child = child;
    }

    @Override
    public void open() {
        if (distinct == null) {
            child.open();
            distinct = new java.util.ArrayList<>();
            seen = new java.util.HashSet<>();
            Object[] r;
            while ((r = child.next()) != null) {
                if (seen.add(java.util.Arrays.asList(r))) distinct.add(r);
            }
            child.close();
        }
        pos = 0;
    }

    @Override
    public Object[] next() {
        if (distinct == null) throw new IllegalStateException("open() 未调用");
        return pos < distinct.size() ? distinct.get(pos++) : null;
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
    public void close() {
    }
}
