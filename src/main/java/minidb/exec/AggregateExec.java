package minidb.exec;

import minidb.btree.BPlusTree;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.Table;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分组聚合（GROUP BY + 聚合函数）。输出行 = [组值..., 聚合值...]。 */
final class GroupByExec implements ExecOp {
    record AggSpec(String func, Expressions.EvalNode arg, boolean star) {}

    private final ExecOp child;
    private final List<Expressions.EvalNode> groupKeys;
    private final List<AggSpec> aggs;
    private final List<String> outCols;
    private java.util.List<Object[]> results;
    private int pos;

    /**
     * @param outCols 形如 "#g0..#gn, #a0..#am"（调用方用 AST 改写把表达式映射到这些列）
     */
    private final boolean globalGroup;

    GroupByExec(ExecOp child, List<Expressions.EvalNode> groupKeys, List<AggSpec> aggs,
                List<String> outCols) {
        this(child, groupKeys, aggs, outCols, groupKeys.isEmpty());
    }

    GroupByExec(ExecOp child, List<Expressions.EvalNode> groupKeys, List<AggSpec> aggs,
                List<String> outCols, boolean globalGroup) {
        this.child = child;
        this.groupKeys = groupKeys;
        this.aggs = aggs;
        this.outCols = outCols;
        this.globalGroup = globalGroup;
    }

    private static final class AggState {
        long count;
        boolean hasValue;
        long longSum;
        double doubleSum;
        Object min;
        Object max;
        boolean sawDouble;
    }

    @Override
    public void open() {
        child.open();
        Map<List<Object>, AggState[]> groups = new LinkedHashMap<>();
        Object[] r;
        while ((r = child.next()) != null) {
            Object[] key = new Object[groupKeys.size()];
            for (int i = 0; i < key.length; i++) key[i] = groupKeys.get(i).eval(r);
            AggState[] st = groups.computeIfAbsent(java.util.Arrays.asList(key), k -> {
                AggState[] a = new AggState[aggs.size()];
                for (int i = 0; i < a.length; i++) a[i] = new AggState();
                return a;
            });
            for (int i = 0; i < aggs.size(); i++) update(st[i], aggs.get(i), r);
        }
        child.close();
        results = new ArrayList<>();
        if (groups.isEmpty() && globalGroup) {
            // 无 GROUP BY 的聚合即使空输入也输出一行（COUNT=0，其余 NULL）
            AggState[] empty = new AggState[aggs.size()];
            for (int i = 0; i < empty.length; i++) empty[i] = new AggState();
            Object[] out = new Object[outCols.size()];
            for (int i = 0; i < aggs.size(); i++) out[i] = finalValue(empty[i], aggs.get(i));
            results.add(out);
        }
        for (var e : groups.entrySet()) {
            Object[] out = new Object[outCols.size()];
            List<Object> key = e.getKey();
            for (int i = 0; i < key.size(); i++) out[i] = key.get(i);
            for (int i = 0; i < aggs.size(); i++) out[groupKeys.size() + i] = finalValue(e.getValue()[i], aggs.get(i));
            results.add(out);
        }
        pos = 0;
    }

    private void update(AggState s, AggSpec agg, Object[] row) {
        if (agg.star()) { // COUNT(*)
            s.count++;
            s.hasValue = true;
            return;
        }
        Object v = agg.arg().eval(row);
        if (v == null) return;
        s.count++;
        s.hasValue = true;
        switch (agg.func()) {
            case "SUM" -> {
                if (v instanceof Double d) {
                    s.sawDouble = true;
                    s.doubleSum += d;
                } else {
                    s.longSum += ((Number) v).longValue();
                }
            }
            case "AVG" -> {
                if (v instanceof Double d) {
                    s.sawDouble = true;
                    s.doubleSum += d;
                } else {
                    s.longSum += ((Number) v).longValue();
                }
            }
            case "MIN" -> {
                if (s.min == null || Expressions.compare(v, s.min) < 0) s.min = v;
            }
            case "MAX" -> {
                if (s.max == null || Expressions.compare(v, s.max) > 0) s.max = v;
            }
            case "COUNT" -> { /* 已计数 */ }
            default -> throw new MiniDbException(MiniDbException.Code.EXEC, "未知聚合 " + agg.func());
        }
    }

    private Object finalValue(AggState s, AggSpec agg) {
        if (agg.func().equals("COUNT")) return (int) s.count; // 空组 COUNT = 0
        if (!s.hasValue) return null;
        return switch (agg.func()) {
            case "SUM" -> s.sawDouble ? (Object) s.doubleSum : (Object) s.longSum;
            case "AVG" -> s.sawDouble ? (Object) (s.doubleSum / s.count)
                    : (Object) ((double) s.longSum / s.count);
            case "MIN" -> s.min;
            case "MAX" -> s.max;
            default -> throw new IllegalStateException();
        };
    }

    @Override
    public Object[] next() {
        if (results == null) throw new IllegalStateException("open() 未调用");
        return pos < results.size() ? results.get(pos++) : null;
    }

    @Override
    public List<String> columns() {
        return outCols;
    }

    @Override
    public String describe() {
        return "GroupBy(" + aggs.size() + " aggs, " + groupKeys.size() + " keys)";
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

/** 通过 B+ 树索引取行（范围由优化器给出）。 */
final class IndexScanExec implements ExecOp {
    private final Table table;
    private final String alias;
    private final BPlusTree tree;
    private final boolean stringKeys;
    private final long lFrom;
    private final long lTo;
    private final String sFrom;
    private final String sTo;
    private final boolean fromInc;
    private final boolean toInc;
    private Iterator<Rid> rids;
    private Rid currentRid;

    IndexScanExec(Table table, String alias, BPlusTree tree,
                  long from, boolean fromInc, long to, boolean toInc) {
        this.table = table;
        this.alias = alias;
        this.tree = tree;
        this.stringKeys = false;
        this.lFrom = from;
        this.lTo = to;
        this.sFrom = null;
        this.sTo = null;
        this.fromInc = fromInc;
        this.toInc = toInc;
    }

    /** VARCHAR 索引扫描：from/to 为 null 表示无界。 */
    IndexScanExec(Table table, String alias, BPlusTree tree,
                  String from, boolean fromInc, String to, boolean toInc) {
        this.table = table;
        this.alias = alias;
        this.tree = tree;
        this.stringKeys = true;
        this.lFrom = 0;
        this.lTo = 0;
        this.sFrom = from;
        this.sTo = to;
        this.fromInc = fromInc;
        this.toInc = toInc;
    }

    @Override
    public void open() {
        rids = (stringKeys
                ? tree.rangeScan(sFrom, fromInc, sTo, toInc)
                : tree.rangeScan(lFrom, fromInc, lTo, toInc)).iterator();
    }

    @Override
    public Object[] next() {
        while (rids != null && rids.hasNext()) {
            Rid rid = rids.next();
            try {
                currentRid = rid;
                return table.get(rid);
            } catch (MiniDbException e) {
                // 索引滞后于行删除时跳过失效 RID（防御）
            }
        }
        return null;
    }

    /** 最近一次 next() 返回行的 RID（行锁用）。 */
    public Rid currentRid() {
        return currentRid;
    }

    @Override
    public List<String> columns() {
        return table.schema().columns().stream()
                .map(c -> alias + "." + c.name()).toList();
    }

    @Override
    public String describe() {
        return "IndexScan(" + alias + ")";
    }

    @Override
    public void close() {
        rids = null;
    }
}
