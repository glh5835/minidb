package minidb.exec;

import minidb.storage.Table;

import java.util.List;

/** 单行算子（无 FROM 的 SELECT）。 */
final class OneRowExec implements ExecOp {
    private final List<Expressions.EvalNode> nodes;
    private final List<String> cols;
    private boolean emitted;

    OneRowExec(List<Expressions.EvalNode> nodes, List<String> cols) {
        this.nodes = nodes;
        this.cols = cols;
    }

    @Override
    public void open() {
        emitted = false;
    }

    @Override
    public Object[] next() {
        if (emitted) return null;
        emitted = true;
        Object[] out = new Object[nodes.size()];
        for (int i = 0; i < out.length; i++) out[i] = nodes.get(i).eval(new Object[0]);
        return out;
    }

    @Override
    public List<String> columns() {
        return cols;
    }

    @Override
    public void close() {
    }
}
