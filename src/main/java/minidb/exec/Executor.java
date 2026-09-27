package minidb.exec;

import minidb.btree.BPlusTree;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.sql.Ast;
import minidb.sql.Parser;
import minidb.sql.Token;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Schema;
import minidb.storage.Table;
import minidb.txn.TxnSession;
import minidb.wal.WalLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语句执行器：解析 → 优化（极简代价模型）→ 火山计划 → 执行。
 *
 * 优化器能力（极简代价：基于表行数估算）：
 *  - WHERE/ON 的 AND 合取拆分，单表谓词下推到扫描
 *  - 等值/范围谓词命中索引列 → IndexScan（估算行数 < 全表一半才走索引）
 *  - 纯 INNER JOIN 按行数估算做贪心 JOIN 顺序（小结果集优先），LEFT JOIN 保持书写顺序
 */
public final class Executor implements Expressions.Binder {
    private final Database db;

    public Executor(Database db) {
        this.db = db;
    }

    public Database db() {
        return db;
    }

    // ---------------- 结果 ----------------

    public record Result(List<String> columns, List<Object[]> rows, String message) {
        public int rowCount() {
            return rows.size();
        }
    }

    private static Result message(String msg) {
        return new Result(List.of(), List.of(), msg);
    }

    // ---------------- 调度 ----------------

    /** REPL 级活动事务（BEGIN/COMMIT/ROLLBACK 语句用） */
    private TxnSession activeSession;

    /** 单条 SQL：无显式事务时按 autoCommit（语句级事务）执行。 */
    public Result execute(String sql) {
        Object stmt = Parser.parse(sql);
        if (stmt instanceof Token.Type t) {
            if (t == Token.Type.BEGIN) {
                if (activeSession != null && !activeSession.finished)
                    throw new MiniDbException(MiniDbException.Code.TXN, "事务已在进行中");
                activeSession = begin(TxnSession.Isolation.REPEATABLE_READ);
                return message("BEGIN");
            }
            if (t == Token.Type.COMMIT) {
                if (activeSession == null || activeSession.finished)
                    throw new MiniDbException(MiniDbException.Code.TXN, "没有活动事务");
                TxnSession s = activeSession;
                activeSession = null;
                commit(s);
                return message("COMMIT");
            }
            if (t == Token.Type.ROLLBACK) {
                if (activeSession == null || activeSession.finished)
                    throw new MiniDbException(MiniDbException.Code.TXN, "没有活动事务");
                TxnSession s = activeSession;
                activeSession = null;
                rollback(s);
                return message("ROLLBACK");
            }
        }
        return execute(stmt, activeSession != null && !activeSession.finished ? activeSession : null);
    }

    public Result execute(Object stmt) {
        return execute(stmt, null);
    }

    /** 在指定事务中执行一条 SQL 文本。 */
    public Result execute(String sql, TxnSession session) {
        return execute(Parser.parse(sql), session);
    }

    /** 在指定事务中执行；session 为 null 时使用语句级 autoCommit 事务。 */
    public Result execute(Object stmt, TxnSession session) {
        boolean auto = session == null;
        boolean readOnlyStmt = stmt instanceof Ast.SelectStmt;
        TxnSession s = auto ? begin(TxnSession.Isolation.READ_COMMITTED, readOnlyStmt) : session;
        try {
            Result r = dispatch(stmt, s);
            if (auto) commit(s);
            return r;
        } catch (RuntimeException e) {
            if (auto) {
                try {
                    rollback(s);
                } catch (RuntimeException ignored) {
                }
            }
            throw e;
        }
    }

    private Result dispatch(Object stmt, TxnSession s) {
        if (stmt instanceof Ast.SelectStmt q) return runSelect(q, s);
        if (stmt instanceof Ast.CreateTableStmt c) return createTable(c);
        if (stmt instanceof Ast.DropTableStmt d) {
            db.dropTable(d.table());
            return message("表 " + d.table() + " 已删除");
        }
        if (stmt instanceof Ast.CreateIndexStmt c) {
            db.createIndex(c.index(), c.table(), c.column());
            return message("索引 " + c.index() + " 已创建");
        }
        if (stmt instanceof Ast.DropIndexStmt d) {
            db.dropIndex(d.index());
            return message("索引 " + d.index() + " 已删除");
        }
        if (stmt instanceof Ast.InsertStmt i) return runInsert(i, s);
        if (stmt instanceof Ast.UpdateStmt u) return runUpdate(u, s);
        if (stmt instanceof Ast.DeleteStmt d) return runDelete(d, s);
        throw new MiniDbException(MiniDbException.Code.EXEC,
                "不支持的语句: " + stmt.getClass().getSimpleName());
    }

    // ---------------- 事务 API ----------------

    /** 开启显式事务。 */
    public TxnSession begin(TxnSession.Isolation isolation) {
        TxnSession s = new TxnSession(db.nextTxnId(), isolation, false,
                db.lockManager(), db.engine().pool());
        db.wal().append(WalLog.BEGIN, s.txnId, "", -1, -1, null, null);
        return s;
    }

    /** autoCommit 语句会话；readOnly 时不写 BEGIN/COMMIT 日志。 */
    private TxnSession begin(TxnSession.Isolation isolation, boolean readOnly) {
        TxnSession s = new TxnSession(db.nextTxnId(), isolation, true,
                db.lockManager(), db.engine().pool(), readOnly);
        if (!readOnly) db.wal().append(WalLog.BEGIN, s.txnId, "", -1, -1, null, null);
        return s;
    }

    /** 提交：日志 fsync → 强制刷出事务 pin 的数据页 → 写 COMMIT 日志（no-steal/force-at-commit）。 */
    public void commit(TxnSession s) {
        if (s.finished) return;
        if (!s.readOnly) db.wal().sync();
        for (int pid : s.pinnedPages) db.engine().pool().flushPage(pid);
        db.engine().flushSystemPages();
        if (!s.readOnly) {
            db.wal().append(WalLog.COMMIT, s.txnId, "", -1, -1, null, null);
            db.wal().sync();
        }
        db.lockManager().releaseAll(s.txnId, s.heldLocks);
        s.unpinAll(false); // 已强制刷盘：干净的 unpin（不能再标脏）
        s.finished = true;
    }

    /** 回滚：逆序执行 undo 动作，释放锁与页。 */
    public void rollback(TxnSession s) {
        if (s.finished) return;
        for (int i = s.undoActions.size() - 1; i >= 0; i--) s.undoActions.get(i).run();
        db.wal().append(WalLog.ABORT, s.txnId, "", -1, -1, null, null);
        db.lockManager().releaseAll(s.txnId, s.heldLocks);
        s.unpinAll(true); // 回滚后的页内容一致，允许脏淘汰
        s.finished = true;
    }

    // ---------------- DDL ----------------

    private Result createTable(Ast.CreateTableStmt c) {
        List<Column> cols = new ArrayList<>();
        for (Ast.ColumnDef cd : c.columns()) {
            Column col = switch (cd.type()) {
                case "INT" -> Column.fixed(cd.name(), ColumnType.INT);
                case "BIGINT" -> Column.fixed(cd.name(), ColumnType.BIGINT);
                case "DOUBLE" -> Column.fixed(cd.name(), ColumnType.DOUBLE);
                case "VARCHAR" -> new Column(cd.name(), ColumnType.VARCHAR,
                        cd.size() == null ? 255 : cd.size());
                default -> throw new MiniDbException(MiniDbException.Code.SCHEMA, "未知类型 " + cd.type());
            };
            cols.add(col);
        }
        if (c.ifNotExists() && db.hasTable(c.table())) {
            return message("表 " + c.table() + " 已存在，跳过创建");
        }
        db.createTable(c.table(), cols);
        return message("表 " + c.table() + " 已创建");
    }

    // ---------------- DML ----------------

    private Result runInsert(Ast.InsertStmt ins, TxnSession s) {
        Table t = db.getTable(ins.table());
        Schema schema = t.schema();
        int n = schema.columns().size();
        int[] map;
        if (ins.columns() == null) {
            map = identity(n);
        } else {
            if (ins.columns().size() != n)
                throw new MiniDbException(MiniDbException.Code.EXEC,
                        "INSERT 必须提供全部 " + n + " 列");
            map = new int[n];
            for (int i = 0; i < n; i++) {
                int idx = schema.columnIndex(ins.columns().get(i));
                if (idx < 0)
                    throw new MiniDbException(MiniDbException.Code.EXEC, "未知列 " + ins.columns().get(i));
                map[idx] = i;
            }
        }
        int count = 0;
        for (List<Ast.Expr> rowExprs : ins.rows()) {
            if (rowExprs.size() != n)
                throw new MiniDbException(MiniDbException.Code.EXEC,
                        "行值数量 " + rowExprs.size() + " != 列数 " + n);
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                Object v = Expressions.bind(rowExprs.get(map[i]), List.of(), this)
                        .eval(new Object[0]);
                values[i] = coerce(v, schema.columns().get(i));
            }
            Rid rid = t.insert(values);
            trackInsert(s, t, rid, values);
            maintainIndexesOnInsert(ins.table(), rid, values, schema);
            count++;
        }
        return message(count + " 行已插入");
    }

    /** 写语句的事务挂钩：行锁 + WAL + no-steal 页 pin + undo 动作。 */
    private void trackInsert(TxnSession s, Table t, Rid rid, Object[] values) {
        if (s == null) return;
        String table = t.schema().tableName();
        s.lockRow(table, rid, false);
        s.pinPage(rid.pageId());
        byte[] after = new minidb.storage.RowCodec(t.schema()).encode(values);
        db.wal().append(WalLog.INSERT, s.txnId, table, rid.pageId(), rid.slot(), null, after);
        s.undoActions.add(() -> {
            if (rowExists(t, rid)) {
                t.delete(rid);
                indexDelete(table, values);
            }
        });
    }

    private static boolean rowExists(Table t, Rid rid) {
        try {
            t.get(rid);
            return true;
        } catch (MiniDbException e) {
            return false;
        }
    }

    private void indexDelete(String table, Object[] row) {
        for (Database.IndexEntry ie : db.indexesFor(table)) {
            int ci = db.getTable(table).schema().columnIndex(ie.meta().column());
            long key = ((Number) row[ci]).longValue();
            ie.tree().delete(key);
        }
    }

    private static int[] identity(int n) {
        int[] m = new int[n];
        for (int i = 0; i < n; i++) m[i] = i;
        return m;
    }

    private void maintainIndexesOnInsert(String table, Rid rid, Object[] values, Schema schema) {
        for (Database.IndexEntry ie : db.indexesFor(table)) {
            int ci = schema.columnIndex(ie.meta().column());
            long key = ((Number) values[ci]).longValue();
            ie.tree().insert(key, rid);
        }
    }

    private void maintainIndexesOnDelete(String table, Rid rid, Object[] values, Schema schema) {
        for (Database.IndexEntry ie : db.indexesFor(table)) {
            int ci = schema.columnIndex(ie.meta().column());
            long key = ((Number) values[ci]).longValue();
            ie.tree().delete(key);
        }
    }

    private Object coerce(Object v, Column col) {
        if (v == null) return null;
        switch (col.type()) {
            case INT -> {
                if (v instanceof Integer i) return i;
                if (v instanceof Long l) {
                    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
                        throw new MiniDbException(MiniDbException.Code.RECORD, "值 " + l + " 超出 INT 范围");
                    return (int) (long) l;
                }
                if (v instanceof Double d) {
                    if (d != Math.rint(d))
                        throw new MiniDbException(MiniDbException.Code.RECORD,
                                "值 " + d + " 不是整数，无法存入 INT 列");
                    return (int) (double) d;
                }
            }
            case BIGINT -> {
                if (v instanceof Integer i) return (long) i;
                if (v instanceof Long l) return l;
                if (v instanceof Double d) {
                    if (d != Math.rint(d))
                        throw new MiniDbException(MiniDbException.Code.RECORD,
                                "值 " + d + " 不是整数，无法存入 BIGINT 列");
                    return (long) (double) d;
                }
            }
            case DOUBLE -> {
                if (v instanceof Number num) return num.doubleValue();
            }
            case VARCHAR -> {
                if (v instanceof String s) return s;
            }
        }
        throw new MiniDbException(MiniDbException.Code.RECORD,
                "值类型与列 " + col.name() + "(" + col.type() + ") 不匹配: " + v);
    }

    private Result runUpdate(Ast.UpdateStmt u, TxnSession s) {
        Table t = db.getTable(u.table());
        Schema schema = t.schema();
        List<String> scope = new ScanExec(t, u.table()).columns();
        List<Expressions.EvalNode> valueNodes = new ArrayList<>();
        int[] colIdx = new int[u.sets().size()];
        for (int i = 0; i < u.sets().size(); i++) {
            var a = u.sets().get(i);
            colIdx[i] = schema.columnIndex(a.column());
            if (colIdx[i] < 0)
                throw new MiniDbException(MiniDbException.Code.EXEC, "未知列 " + a.column());
            valueNodes.add(Expressions.bind(a.value(), scope, this));
        }
        Expressions.EvalNode where = u.where() == null ? null : Expressions.bind(u.where(), scope, this);
        // 先收集命中行（逐行加行锁）再修改；扫描走优化器（索引点更新只锁命中行）
        List<Rid> hits = new ArrayList<>();
        try (ExecOp scanOp = buildScanForWrite(t, u.table(), u.where())) {
            scanOp.open();
            Object[] r;
            while ((r = scanOp.next()) != null) {
                Rid rid = ridOf(scanOp);
                boolean fresh = s != null && s.lockRow(u.table(), rid, false);
                boolean match = where == null || Expressions.truthy(where.eval(r));
                if (fresh && !match) s.unlockRow(u.table(), rid); // 仅放本次新获取且未命中的锁
                if (match) hits.add(rid);
            }
        }
        for (Rid rid : hits) {
            Object[] old = t.get(rid);
            Object[] updated = old.clone();
            for (int i = 0; i < valueNodes.size(); i++) {
                Object v = valueNodes.get(i).eval(old);
                updated[colIdx[i]] = coerce(v, schema.columns().get(colIdx[i]));
            }
            if (s != null) {
                s.pinPage(rid.pageId());
                db.wal().append(WalLog.UPDATE, s.txnId, u.table(), rid.pageId(), rid.slot(),
                        new minidb.storage.RowCodec(schema).encode(old),
                        new minidb.storage.RowCodec(schema).encode(updated));
            }
            for (Database.IndexEntry ie : db.indexesFor(u.table())) {
                int ci = schema.columnIndex(ie.meta().column());
                // 索引列值未变时不动索引（避免并发下同键删/插窗口让其他事务的索引扫描漏行）
                if (((Number) old[ci]).longValue() != ((Number) updated[ci]).longValue()) {
                    ie.tree().delete(((Number) old[ci]).longValue());
                }
            }
            Rid newRid = t.update(rid, updated);
            for (Database.IndexEntry ie : db.indexesFor(u.table())) {
                int ci = schema.columnIndex(ie.meta().column());
                if (((Number) old[ci]).longValue() != ((Number) updated[ci]).longValue()) {
                    ie.tree().insert(((Number) updated[ci]).longValue(), newRid);
                }
            }
            if (s != null) {
                s.pinPage(newRid.pageId());
                s.undoActions.add(() -> {
                    if (rowExists(t, newRid)) {
                        Rid back = t.update(newRid, old);
                        for (Database.IndexEntry ie : db.indexesFor(u.table())) {
                            int ci = schema.columnIndex(ie.meta().column());
                            ie.tree().delete(((Number) updated[ci]).longValue());
                            ie.tree().insert(((Number) old[ci]).longValue(), back);
                        }
                    }
                });
            }
        }
        return message(hits.size() + " 行已更新");
    }

    private Result runDelete(Ast.DeleteStmt d, TxnSession s) {
        Table t = db.getTable(d.table());
        Schema schema = t.schema();
        List<String> scope = new ScanExec(t, d.table()).columns();
        Expressions.EvalNode where = d.where() == null ? null : Expressions.bind(d.where(), scope, this);
        List<Rid> hits = new ArrayList<>();
        List<Object[]> oldRows = new ArrayList<>();
        try (ExecOp scanOp = buildScanForWrite(t, d.table(), d.where())) {
            scanOp.open();
            Object[] r;
            while ((r = scanOp.next()) != null) {
                Rid rid = ridOf(scanOp);
                boolean fresh = s != null && s.lockRow(d.table(), rid, false);
                boolean match = where == null || Expressions.truthy(where.eval(r));
                if (fresh && !match) s.unlockRow(d.table(), rid);
                if (match) {
                    hits.add(rid);
                    oldRows.add(r.clone());
                }
            }
        }
        for (int i = 0; i < hits.size(); i++) {
            Rid rid = hits.get(i);
            Object[] old = oldRows.get(i);
            if (s != null) {
                s.pinPage(rid.pageId());
                db.wal().append(WalLog.DELETE, s.txnId, d.table(), rid.pageId(), rid.slot(),
                        new minidb.storage.RowCodec(schema).encode(old), null);
            }
            maintainIndexesOnDelete(d.table(), rid, old, schema);
            t.delete(rid);
            if (s != null) {
                s.undoActions.add(() -> {
                    if (!rowExists(t, rid)) {
                        t.restoreAt(rid, old);
                        for (Database.IndexEntry ie : db.indexesFor(d.table())) {
                            int ci = schema.columnIndex(ie.meta().column());
                            ie.tree().insert(((Number) old[ci]).longValue(), rid);
                        }
                    }
                });
            }
        }
        return message(hits.size() + " 行已删除");
    }

    // ---------------- SELECT ----------------

    /** 生成计划描述文本（调试/优化器验证用）。 */
    public String explain(String sql) {
        Object stmt = Parser.parse(sql);
        if (!(stmt instanceof Ast.SelectStmt s))
            throw new MiniDbException(MiniDbException.Code.EXEC, "EXPLAIN 仅支持 SELECT");
        StringBuilder sb = new StringBuilder();
        describeTree(planSelect(s), sb, 0);
        return sb.toString();
    }

    private void describeTree(ExecOp op, StringBuilder sb, int depth) {
        sb.append("  ".repeat(Math.max(0, depth))).append(op.describe()).append('\n');
        for (ExecOp child : op.children()) describeTree(child, sb, depth + 1);
    }

    public Result runSelect(Ast.SelectStmt s) {
        return runSelect(s, null);
    }

    public Result runSelect(Ast.SelectStmt s, TxnSession session) {
        ExecOp plan = planSelect(s, session);
        List<String> cols = plan.columns();
        List<Object[]> rows = new ArrayList<>();
        try {
            plan.open();
            Object[] r;
            while ((r = plan.next()) != null) rows.add(r);
        } finally {
            ExecOp.closeQuietly(plan);
        }
        return new Result(cols, rows, null);
    }

    private ExecOp planSelect(Ast.SelectStmt s) {
        return planSelect(s, null);
    }

    private ExecOp planSelect(Ast.SelectStmt s, TxnSession session) {
        if (s.from() == null) return planNoFrom(s);
        FromInfo from = flattenFrom(s.from());
        List<String> scope = from.scope();
        // WHERE 拆合取
        List<Ast.Expr> conjuncts = s.where() == null ? new ArrayList<>() : Expressions.flattenAnd(s.where());
        // 有 LEFT JOIN 时不做任何谓词下推（右表谓词下推会改变匹配结果，
        // 左表/右表 WHERE 谓词一律 join 后过滤，保证语义正确）
        // 纯 INNER 时：单表谓词（含单表 ON）下推到扫描，跨表的做连接谓词
        List<Ast.Expr> joinPreds = new ArrayList<>();
        for (Ast.Expr c : from.joinConds) {
            if (from.hasLeft) continue; // 已在 planFromInOrder 的 ON 里
            String sole = soleTable(c, from);
            if (sole != null) byTable(from, sole).add(c);
            else joinPreds.add(c);
        }
        for (Ast.Expr c : conjuncts) {
            String sole = from.hasLeft ? null : soleTable(c, from);
            if (sole != null) byTable(from, sole).add(c);
            else joinPreds.add(c);
        }
        // 子查询在连接谓词中不支持参与重排：包含子查询的谓词放到 join 后过滤
        List<Ast.Expr> postPreds = new ArrayList<>();
        for (var it = joinPreds.iterator(); it.hasNext(); ) {
            Ast.Expr p = it.next();
            if (containsSubquery(p)) {
                postPreds.add(p);
                it.remove();
            }
        }
        ExecOp scanPlan = from.hasLeft
                ? planFromInOrder(from, s.from(), session)
                : planJoinGreedy(from, joinPreds, session);
        // 未被用作 JOIN ON 的谓词（引用外层列的相关谓词等）join 后过滤
        if (!joinPreds.isEmpty()) {
            scanPlan = new FilterExec(scanPlan,
                    Expressions.bind(Expressions.andAll(joinPreds), scanPlan.columns(), this));
        }
        if (!postPreds.isEmpty()) {
            scanPlan = new FilterExec(scanPlan,
                    Expressions.bind(Expressions.andAll(postPreds), scanPlan.columns(), this));
        }
        return planTail(s, scanPlan);
    }

    private static List<Ast.Expr> byTable(FromInfo from, String alias) {
        for (RelInfo r : from.rels)
            if (r.alias.equals(alias)) return r.preds;
        throw new MiniDbException(MiniDbException.Code.EXEC, "内部错误：无表 " + alias);
    }

    // ---------------- FROM 展平 ----------------

    private static final class RelInfo {
        String table;
        String alias;
        Table t;
        double baseRows;
        List<Ast.Expr> preds = new ArrayList<>();
        List<String> cols;
    }

    private static final class FromInfo {
        List<RelInfo> rels = new ArrayList<>();
        boolean hasLeft;
        List<Ast.Expr> joinConds = new ArrayList<>();

        List<String> scope() {
            List<String> out = new ArrayList<>();
            for (RelInfo r : rels) out.addAll(r.cols);
            return out;
        }
    }

    private FromInfo flattenFrom(Ast.TableRef ref) {
        FromInfo info = new FromInfo();
        flattenFromRec(ref, info);
        Set<String> seen = new HashSet<>();
        for (RelInfo r : info.rels)
            if (!seen.add(r.alias.toLowerCase()))
                throw new MiniDbException(MiniDbException.Code.EXEC,
                        "表 " + r.alias + " 被引用多次：自连接请使用别名（FROM t a, t b）");
        for (RelInfo r : info.rels) {
            r.baseRows = Math.max(1, r.t.rowCount());
            r.cols = r.t.schema().columns().stream()
                    .map(c -> r.alias + "." + c.name()).toList();
        }
        return info;
    }

    private void flattenFromRec(Ast.TableRef ref, FromInfo info) {
        if (ref instanceof Ast.NamedTable nt) {
            RelInfo r = new RelInfo();
            r.table = nt.name();
            r.alias = nt.alias() != null ? nt.alias() : nt.name();
            r.t = db.getTable(nt.name());
            info.rels.add(r);
        } else if (ref instanceof Ast.Join j) {
            if (j.type().equals("LEFT")) info.hasLeft = true;
            flattenFromRec(j.left(), info);
            flattenFromRec(j.right(), info);
            if (j.on() != null) info.joinConds.addAll(Expressions.flattenAnd(j.on()));
        }
    }

    /** 谓词引用的列是否全属于一个表；返回别名或 null。 */
    private String soleTable(Ast.Expr e, FromInfo from) {
        List<Ast.ColRef> cols = new ArrayList<>();
        Expressions.collectColumns(e, cols);
        if (cols.isEmpty()) return null;
        String tbl = null;
        for (Ast.ColRef c : cols) {
            String owner = resolveOwner(c, from);
            if (owner == null) return null;
            if (tbl == null) tbl = owner;
            else if (!tbl.equals(owner)) return null;
        }
        return tbl;
    }

    private String resolveOwner(Ast.ColRef c, FromInfo from) {
        if (c.table() != null) {
            for (RelInfo r : from.rels)
                if (r.alias.equalsIgnoreCase(c.table())) return r.alias;
            return null; // 外层相关列
        }
        String hit = null;
        for (RelInfo r : from.rels) {
            if (r.t.schema().columnIndex(c.column()) >= 0) {
                if (hit != null)
                    throw new MiniDbException(MiniDbException.Code.EXEC, "列名有歧义: " + c.column());
                hit = r.alias;
            }
        }
        return hit;
    }

    // ---------------- 扫描与 JOIN 计划 ----------------

    private record ScanChoice(ExecOp op, double rows) {}

    private static Rid ridOf(ExecOp scan) {
        if (scan instanceof ScanExec se) return se.currentRid();
        if (scan instanceof IndexScanExec ix) return ix.currentRid();
        throw new MiniDbException(MiniDbException.Code.EXEC, "写扫描必须提供 RID");
    }

    /** 写语句（UPDATE/DELETE）的扫描：按 WHERE 谓词复用索引选择，避免全表扫描的无谓行阻塞。 */
    private ExecOp buildScanForWrite(Table t, String alias, Ast.Expr where) {
        RelInfo r = new RelInfo();
        r.table = t.schema().tableName();
        r.alias = alias;
        r.t = t;
        r.baseRows = Math.max(1, t.rowCount());
        r.cols = t.schema().columns().stream().map(c -> alias + "." + c.name()).toList();
        r.preds = where == null ? new ArrayList<>() : Expressions.flattenAnd(where);
        try {
            ScanChoice sc = chooseScan(r);
            return sc.op();
        } catch (RuntimeException e) {
            return new ScanExec(t, alias);
        }
    }

    private ScanChoice chooseScan(RelInfo r) {
        // 按列聚合范围谓词：同列多个 > < >= <= 合并成一段（[, ]），等值锚定上下界
        java.util.Map<String, List<Ast.Expr>> byCol = new java.util.LinkedHashMap<>();
        java.util.Map<String, long[]> bounds = new java.util.LinkedHashMap<>(); // [from, to]
        java.util.Map<String, boolean[]> incs = new java.util.LinkedHashMap<>(); // [fromInc, toInc]
        java.util.Map<String, Boolean> eq = new java.util.LinkedHashMap<>();
        List<Ast.Expr> consumed = new ArrayList<>();
        for (Ast.Expr p : r.preds) {
            IndexRange ir = indexRangeFor(p, r);
            if (ir == null) continue;
            boolean hasIndex = false;
            for (Database.IndexEntry ie : db.indexesFor(r.table))
                if (ie.meta().column().equalsIgnoreCase(ir.column)) hasIndex = true;
            if (!hasIndex) continue;
            List<Ast.Expr> list = byCol.computeIfAbsent(ir.column, k -> new ArrayList<>());
            list.add(p);
            long[] b = bounds.computeIfAbsent(ir.column, k -> new long[]{Long.MIN_VALUE, Long.MAX_VALUE});
            boolean[] inc = incs.computeIfAbsent(ir.column, k -> new boolean[]{true, true});
            if (ir.kind == 0) {
                b[0] = ir.from;
                b[1] = ir.to;
                inc[0] = true;
                inc[1] = true;
                eq.put(ir.column, true);
            } else {
                if (ir.fromInc || ir.from > b[0] || (ir.from == b[0] && ir.fromInc)) {
                    if (ir.from > b[0]) {
                        b[0] = ir.from;
                        inc[0] = ir.fromInc;
                    } else if (ir.from == b[0] && !ir.fromInc) {
                        inc[0] = false;
                    }
                }
                if (ir.to < b[1]) {
                    b[1] = ir.to;
                    inc[1] = ir.toInc;
                } else if (ir.to == b[1] && !ir.toInc) {
                    inc[1] = false;
                }
                eq.putIfAbsent(ir.column, false);
            }
        }
        String bestCol = null;
        Database.IndexEntry bestIdx = null;
        double bestRows = Double.MAX_VALUE;
        for (var col : byCol.entrySet()) {
            long[] b = bounds.get(col.getKey());
            boolean[] inc = incs.get(col.getKey());
            if (b[0] > b[1] || (b[0] == b[1] && !(inc[0] && inc[1]))) {
                continue; // 空区间交给常规过滤
            }
            for (Database.IndexEntry ie : db.indexesFor(r.table)) {
                if (!ie.meta().column().equalsIgnoreCase(col.getKey())) continue;
                int nBounds = col.getValue().size();
                double est = Boolean.TRUE.equals(eq.get(col.getKey()))
                        ? Math.max(1, r.baseRows / 100.0)
                        : Math.max(1, r.baseRows / Math.pow(3, Math.min(2, nBounds)));
                if (est < bestRows) {
                    bestRows = est;
                    bestIdx = ie;
                    bestCol = col.getKey();
                }
            }
        }
        if (bestCol != null && bestRows < r.baseRows * 0.5) {
            consumed.addAll(byCol.get(bestCol));
            r.preds.removeAll(consumed); // 已被索引覆盖
            long[] b = bounds.get(bestCol);
            boolean[] inc = incs.get(bestCol);
            return new ScanChoice(new IndexScanExec(r.t, r.alias, bestIdx.tree(),
                    b[0], inc[0], b[1], inc[1]), bestRows);
        }
        return new ScanChoice(new ScanExec(r.t, r.alias), r.baseRows);
    }

    private static final class IndexRange {
        String column;
        long from;
        boolean fromInc;
        long to;
        boolean toInc;
        int kind; // 0=等值 1=范围
    }

    private IndexRange indexRangeFor(Ast.Expr p, RelInfo r) {
        if (!(p instanceof Ast.BinOp b)) return null;
        String op = b.op();
        if (!List.of("=", "<", "<=", ">", ">=").contains(op)) return null;
        Ast.Expr a = b.left(), c = b.right();
        Ast.ColRef col;
        Object lit;
        if (a instanceof Ast.ColRef cr && c instanceof Ast.Literal l) {
            col = cr;
            lit = l.value();
        } else if (a instanceof Ast.Literal l2 && c instanceof Ast.ColRef cr2) {
            col = cr2;
            lit = l2.value();
            op = switch (op) {
                case "<" -> ">";
                case "<=" -> ">=";
                case ">" -> "<";
                case ">=" -> "<=";
                default -> op;
            };
        } else {
            return null;
        }
        if (lit == null) return null;
        if (col.table() != null && !col.table().equalsIgnoreCase(r.alias)) return null;
        Column column;
        try {
            column = r.t.schema().column(col.column());
        } catch (MiniDbException e) {
            return null;
        }
        if (column.type() != ColumnType.INT && column.type() != ColumnType.BIGINT) return null;
        if (!(lit instanceof Number num)) return null;
        long v = num.longValue();
        IndexRange ir = new IndexRange();
        ir.column = column.name();
        switch (op) {
            case "=" -> {
                ir.kind = 0;
                ir.from = v;
                ir.fromInc = true;
                ir.to = v;
                ir.toInc = true;
            }
            case ">" -> {
                ir.kind = 1;
                ir.from = v;
                ir.fromInc = false;
                ir.to = Long.MAX_VALUE;
                ir.toInc = true;
            }
            case ">=" -> {
                ir.kind = 1;
                ir.from = v;
                ir.fromInc = true;
                ir.to = Long.MAX_VALUE;
                ir.toInc = true;
            }
            case "<" -> {
                ir.kind = 1;
                ir.from = Long.MIN_VALUE;
                ir.fromInc = true;
                ir.to = v;
                ir.toInc = false;
            }
            case "<=" -> {
                ir.kind = 1;
                ir.from = Long.MIN_VALUE;
                ir.fromInc = true;
                ir.to = v;
                ir.toInc = true;
            }
            default -> {
                return null;
            }
        }
        return ir;
    }

    /** 事务读：给叶扫描包上行锁算子。 */
    private ExecOp wrapLock(ExecOp op, RelInfo rel, TxnSession session) {
        if (session == null || session.finished) return op;
        return new LockExec(op, session, rel.t.schema().tableName());
    }

    private ExecOp withFilter(ExecOp op, List<Ast.Expr> preds) {
        if (preds == null || preds.isEmpty()) return op;
        return new FilterExec(op, Expressions.bind(Expressions.andAll(preds), op.columns(), this));
    }

    /** 纯 INNER：贪心 JOIN 顺序 */
    private ExecOp planJoinGreedy(FromInfo from, List<Ast.Expr> joinPreds, TxnSession session) {
        List<RelInfo> rels = from.rels;
        java.util.Map<String, ExecOp> scans = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> card = new java.util.LinkedHashMap<>();
        for (RelInfo r : rels) {
            r.preds = byTable(from, r.alias); // chooseScan 会移除被索引覆盖的谓词
            ScanChoice sc = chooseScan(r);
            scans.put(r.alias, wrapLock(sc.op(), r, session));
            card.put(r.alias, sc.rows());
        }
        List<String> order = new ArrayList<>();
        Set<String> joined = new HashSet<>();
        order.add(minBy(card));
        joined.add(order.get(0));
        while (order.size() < rels.size()) {
            String best = null;
            double bestCost = Double.MAX_VALUE;
            double curSize = productOf(card, order);
            for (RelInfo r : rels) {
                if (joined.contains(r.alias)) continue;
                double sel = hasJoinPredWith(joinPreds, r.alias, joined, from) ? 0.1 : 1.0;
                double cost = curSize * card.get(r.alias) * sel;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = r.alias;
                }
            }
            order.add(best);
            joined.add(best);
        }
        ExecOp acc = withFilter(scans.get(order.get(0)), findRel(from, order.get(0)).preds);
        for (int i = 1; i < order.size(); i++) {
            String alias = order.get(i);
            Set<String> joinedSoFar = new HashSet<>(order.subList(0, i));
            RelInfo rel = findRel(from, alias);
            ExecOp right = withFilter(scans.get(alias), rel.preds);
            List<Ast.Expr> on = takeJoinPredsBetween(joinPreds, alias, joinedSoFar, from);
            List<String> scope = concat(acc.columns(), right.columns());
            Expressions.EvalNode onNode = on.isEmpty() ? null
                    : Expressions.bind(Expressions.andAll(on), scope, this);
            acc = new NestedLoopJoinExec(acc, right, false, onNode);
        }
        return acc;
    }

    /** 有 LEFT JOIN：保持书写顺序 */
    private ExecOp planFromInOrder(FromInfo from, Ast.TableRef ref, TxnSession session) {
        if (ref instanceof Ast.NamedTable nt) {
            String alias = nt.alias() != null ? nt.alias() : nt.name();
            RelInfo rel = findRel(from, alias);
            rel.preds = byTable(from, alias);
            ScanChoice sc = chooseScan(rel);
            return withFilter(wrapLock(sc.op(), rel, session), rel.preds);
        }
        Ast.Join j = (Ast.Join) ref;
        ExecOp left = planFromInOrder(from, j.left(), session);
        ExecOp right = planFromInOrder(from, j.right(), session);
        Expressions.EvalNode on = j.on() == null ? null
                : Expressions.bind(j.on(), concat(left.columns(), right.columns()), this);
        return new NestedLoopJoinExec(left, right, j.type().equals("LEFT"), on);
    }

    private RelInfo findRel(FromInfo from, String alias) {
        for (RelInfo r : from.rels)
            if (r.alias.equals(alias)) return r;
        throw new MiniDbException(MiniDbException.Code.EXEC, "内部错误：找不到关系 " + alias);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private String minBy(Map<String, Double> card) {
        String best = null;
        double v = Double.MAX_VALUE;
        for (var e : card.entrySet())
            if (e.getValue() < v) {
                v = e.getValue();
                best = e.getKey();
            }
        if (best == null) throw new MiniDbException(MiniDbException.Code.EXEC, "FROM 为空");
        return best;
    }

    private double productOf(Map<String, Double> card, List<String> order) {
        double p = 1;
        for (String a : order) p *= card.get(a);
        return p;
    }

    private boolean hasJoinPredWith(List<Ast.Expr> joinPreds, String alias, Set<String> joined, FromInfo from) {
        for (Ast.Expr p : joinPreds) {
            Set<String> owners = ownersOf(p, from);
            if (owners.contains(alias) && owners.stream().anyMatch(joined::contains)) return true;
        }
        return false;
    }

    /** 取走连接 alias 与已连接表之间的谓词 */
    private List<Ast.Expr> takeJoinPredsBetween(List<Ast.Expr> joinPreds, String alias,
                                                Set<String> joinedSoFar, FromInfo from) {
        List<Ast.Expr> out = new ArrayList<>();
        for (var it = joinPreds.iterator(); it.hasNext(); ) {
            Ast.Expr p = it.next();
            Set<String> owners = ownersOf(p, from);
            if (owners.contains(alias) && owners.stream().anyMatch(joinedSoFar::contains)) {
                out.add(p);
                it.remove();
            }
        }
        return out;
    }

    private Set<String> ownersOf(Ast.Expr p, FromInfo from) {
        Set<String> out = new HashSet<>();
        for (Ast.ColRef c : columnRefs(p)) {
            String o = resolveOwner(c, from);
            if (o != null) out.add(o);
        }
        return out;
    }

    private List<Ast.ColRef> columnRefs(Ast.Expr e) {
        List<Ast.ColRef> out = new ArrayList<>();
        Expressions.collectColumns(e, out);
        return out;
    }

    private boolean containsSubquery(Ast.Expr e) {
        if (e == null) return false;
        if (e instanceof Ast.InQueryOp || e instanceof Ast.ExistsOp || e instanceof Ast.ScalarQueryOp)
            return true;
        if (e instanceof Ast.BinOp b) return containsSubquery(b.left()) || containsSubquery(b.right());
        if (e instanceof Ast.UnaryOp u) return containsSubquery(u.operand());
        if (e instanceof Ast.InListOp in) {
            if (containsSubquery(in.operand())) return true;
            for (Ast.Expr it : in.items()) if (containsSubquery(it)) return true;
        }
        return false;
    }

    // ---------------- SELECT 尾部 ----------------

    private ExecOp planTail(Ast.SelectStmt s, ExecOp input) {
        boolean hasAgg = false;
        for (Ast.SelectItem item : s.items())
            if (!item.star() && containsAggregate(item.expr())) hasAgg = true;
        if (s.having() != null && containsAggregate(s.having())) hasAgg = true;

        if (s.groupBy() != null || hasAgg) return planGroupTail(s, input);

        // 非聚合路径
        List<String> inCols = input.columns();
        // ORDER BY：先尽量绑定到输出列（别名），绑不上的（且非 DISTINCT）排到投影前
        List<SortExec.SortKey> preKeys = new ArrayList<>();
        List<SortExec.SortKey> postKeys = new ArrayList<>();
        if (s.orderBy() != null) {
            List<String> outNames = outputNames(s, inCols);
            for (Ast.OrderItem oi : s.orderBy()) {
                Expressions.EvalNode node = tryBindOutput(oi.expr(), outNames);
                if (node != null) postKeys.add(new SortExec.SortKey(node, oi.desc()));
                else if (!s.distinct()) preKeys.add(new SortExec.SortKey(
                        Expressions.bind(oi.expr(), inCols, this), oi.desc()));
                else throw new MiniDbException(MiniDbException.Code.EXEC,
                        "DISTINCT 查询的 ORDER BY 必须引用输出列");
            }
        }
        ExecOp op = input;
        if (!preKeys.isEmpty()) op = new SortExec(op, preKeys);
        op = buildProject(s, op);
        if (s.distinct()) op = new DistinctExec(op);
        if (!postKeys.isEmpty()) op = new SortExec(op, postKeys);
        if (s.limit() != null || s.offset() != null) op = new LimitExec(op, s.limit(), s.offset());
        return op;
    }

    private List<String> outputNames(Ast.SelectStmt s, List<String> inCols) {
        List<String> out = new ArrayList<>();
        for (Ast.SelectItem item : s.items()) {
            if (item.star()) out.addAll(inCols);
            else out.add(item.alias() != null ? item.alias() : defaultName(item.expr(), inCols));
        }
        return out;
    }

    /** 表达式若恰好是某输出列名 → 返回按下标取值的节点 */
    private Expressions.EvalNode tryBindOutput(Ast.Expr e, List<String> outNames) {
        if (!(e instanceof Ast.ColRef cr) || cr.table() != null) return null;
        int k = indexOfName(outNames, cr.column());
        if (k < 0) return null;
        int fk = k;
        return row -> row[fk];
    }

    private static int indexOfName(List<String> names, String want) {
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            int dot = n.lastIndexOf('.');
            String bare = dot >= 0 ? n.substring(dot + 1) : n;
            if (bare.equalsIgnoreCase(want) || n.equalsIgnoreCase(want)) return i;
        }
        return -1;
    }

    private ExecOp buildProject(Ast.SelectStmt s, ExecOp input) {
        List<String> inCols = input.columns();
        List<Expressions.EvalNode> nodes = new ArrayList<>();
        List<String> outCols = new ArrayList<>();
        for (Ast.SelectItem item : s.items()) {
            if (item.star()) {
                for (int i = 0; i < inCols.size(); i++) {
                    int fi = i;
                    nodes.add(row -> row[fi]);
                    outCols.add(inCols.get(i));
                }
            } else {
                nodes.add(Expressions.bind(item.expr(), inCols, this));
                outCols.add(item.alias() != null ? item.alias() : defaultName(item.expr(), inCols));
            }
        }
        return new ProjectExec(input, nodes, outCols);
    }

    private String defaultName(Ast.Expr e, List<String> inCols) {
        if (e instanceof Ast.ColRef c && c.table() != null) return c.table() + "." + c.column();
        if (e instanceof Ast.ColRef c) {
            for (String col : inCols) {
                int dot = col.lastIndexOf('.');
                if ((dot >= 0 ? col.substring(dot + 1) : col).equalsIgnoreCase(c.column())) return col;
            }
            return c.column();
        }
        return astText(e);
    }

    private boolean containsAggregate(Ast.Expr e) {
        if (e == null) return false;
        if (e instanceof Ast.FuncCall f)
            return List.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(f.name());
        if (e instanceof Ast.BinOp b) return containsAggregate(b.left()) || containsAggregate(b.right());
        if (e instanceof Ast.UnaryOp u) return containsAggregate(u.operand());
        return false;
    }

    /** 聚合路径：Group → [HAVING] → Sort(按组输出) → Project → DISTINCT → LIMIT */
    private ExecOp planGroupTail(Ast.SelectStmt s, ExecOp input) {
        List<Ast.Expr> groupAsts = s.groupBy() == null ? List.of() : s.groupBy();
        LinkedHashSet<Ast.FuncCall> aggSet = new LinkedHashSet<>();
        for (Ast.SelectItem item : s.items())
            if (!item.star()) collectAggs(item.expr(), aggSet);
        if (s.having() != null) collectAggs(s.having(), aggSet);
        List<Ast.FuncCall> aggs = new ArrayList<>(aggSet);

        List<String> inCols = input.columns();
        List<Expressions.EvalNode> groupNodes = new ArrayList<>();
        List<String> groupScope = new ArrayList<>();
        for (int i = 0; i < groupAsts.size(); i++) {
            groupNodes.add(Expressions.bind(groupAsts.get(i), inCols, this));
            groupScope.add("#g" + i);
        }
        List<GroupByExec.AggSpec> specs = new ArrayList<>();
        for (Ast.FuncCall f : aggs) {
            Expressions.EvalNode arg = f.star() ? null : Expressions.bind(f.arg(), inCols, this);
            specs.add(new GroupByExec.AggSpec(f.name(), arg, f.star()));
            groupScope.add("#a" + (specs.size() - 1));
        }
        if (groupAsts.isEmpty() && !inCols.isEmpty() && s.groupBy() == null) {
            // 无 GROUP BY 的聚合：单组（无行时也输出一行）
        } else if (s.groupBy() == null && s.items().size() > 0 && inCols.isEmpty()) {
            // 无 FROM 聚合——不会到这里（planNoFrom 处理）
        }
        ExecOp op = new GroupByExec(input, groupNodes, specs, groupScope);
        if (s.having() != null) {
            Ast.Expr havingExpr = substituteAlias(s.having(), s, groupAsts, aggs);
            Ast.Expr rewritten = rewriteGroupExpr(havingExpr, groupAsts, aggs);
            op = new FilterExec(op, Expressions.bind(rewritten, groupScope, this));
        }
        // ORDER BY 绑到组输出（支持别名）
        List<SortExec.SortKey> keys = new ArrayList<>();
        if (s.orderBy() != null) {
            for (Ast.OrderItem oi : s.orderBy()) {
                Ast.Expr bound = substituteAlias(oi.expr(), s, groupAsts, aggs);
                keys.add(new SortExec.SortKey(
                        Expressions.bind(rewriteGroupExpr(bound, groupAsts, aggs), groupScope, this),
                        oi.desc()));
            }
        }
        if (!keys.isEmpty()) op = new SortExec(op, keys);
        // 投影
        List<Expressions.EvalNode> nodes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Ast.SelectItem item : s.items()) {
            if (item.star())
                throw new MiniDbException(MiniDbException.Code.EXEC, "聚合查询不支持 *");
            Ast.Expr rewritten = rewriteGroupExpr(item.expr(), groupAsts, aggs);
            nodes.add(Expressions.bind(rewritten, groupScope, this));
            names.add(item.alias() != null ? item.alias() : defaultName(item.expr(), inCols));
        }
        op = new ProjectExec(op, nodes, names);
        if (s.distinct()) op = new DistinctExec(op);
        if (s.limit() != null || s.offset() != null) op = new LimitExec(op, s.limit(), s.offset());
        return op;
    }

    /** ORDER BY / HAVING 里的别名（递归地）替换为对应 SELECT 表达式 */
    private Ast.Expr substituteAlias(Ast.Expr e, Ast.SelectStmt s, List<Ast.Expr> groupAsts,
                                     List<Ast.FuncCall> aggs) {
        if (e == null) return null;
        if (e instanceof Ast.ColRef cr && cr.table() == null) {
            for (Ast.SelectItem item : s.items()) {
                if (item.star() || item.alias() == null) continue;
                if (item.alias().equalsIgnoreCase(cr.column())) return item.expr();
            }
            return e;
        }
        if (e instanceof Ast.BinOp b)
            return new Ast.BinOp(b.op(), substituteAlias(b.left(), s, groupAsts, aggs),
                    substituteAlias(b.right(), s, groupAsts, aggs));
        if (e instanceof Ast.UnaryOp u)
            return new Ast.UnaryOp(u.op(), substituteAlias(u.operand(), s, groupAsts, aggs));
        return e;
    }

    private void collectAggs(Ast.Expr e, Set<Ast.FuncCall> out) {
        if (e == null) return;
        if (e instanceof Ast.FuncCall f
                && List.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(f.name())) {
            out.add(f);
            return;
        }
        if (e instanceof Ast.BinOp b) {
            collectAggs(b.left(), out);
            collectAggs(b.right(), out);
        } else if (e instanceof Ast.UnaryOp u) {
            collectAggs(u.operand(), out);
        }
    }

    /** 表达式中的 group 项替换为 #gN、聚合替换为 #aN。 */
    private Ast.Expr rewriteGroupExpr(Ast.Expr e, List<Ast.Expr> groupAsts, List<Ast.FuncCall> aggs) {
        int gi = indexOfSame(groupAsts, e);
        if (gi >= 0) return new Ast.ColRef(null, "#g" + gi);
        if (e instanceof Ast.FuncCall f
                && List.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(f.name())) {
            int ai = aggs.indexOf(f);
            if (ai < 0)
                throw new MiniDbException(MiniDbException.Code.EXEC, "未收集到聚合 " + f.name());
            return new Ast.ColRef(null, "#a" + ai);
        }
        if (e instanceof Ast.BinOp b)
            return new Ast.BinOp(b.op(), rewriteGroupExpr(b.left(), groupAsts, aggs),
                    rewriteGroupExpr(b.right(), groupAsts, aggs));
        if (e instanceof Ast.UnaryOp u)
            return new Ast.UnaryOp(u.op(), rewriteGroupExpr(u.operand(), groupAsts, aggs));
        if (e instanceof Ast.ColRef) {
            throw new MiniDbException(MiniDbException.Code.EXEC,
                    "列 " + ((Ast.ColRef) e).column() + " 必须出现在 GROUP BY 或聚合函数中");
        }
        return e;
    }

    private static int indexOfSame(List<Ast.Expr> list, Ast.Expr e) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).equals(e)) return i;
        return -1;
    }

    /** 无 FROM 的 SELECT：单行常量 */
    private ExecOp planNoFrom(Ast.SelectStmt s) {
        List<Expressions.EvalNode> nodes = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        for (Ast.SelectItem item : s.items()) {
            if (item.star())
                throw new MiniDbException(MiniDbException.Code.EXEC, "无 FROM 时不能用 *");
            nodes.add(Expressions.bind(item.expr(), List.of(), this));
            cols.add(item.alias() != null ? item.alias() : astText(item.expr()));
        }
        return new OneRowExec(nodes, cols);
    }

    // ---------------- 表达式文本化 ----------------

    static String astText(Ast.Expr e) {
        if (e instanceof Ast.Literal l) return l.value() == null ? "NULL" : String.valueOf(l.value());
        if (e instanceof Ast.ColRef c) return c.table() != null ? c.table() + "." + c.column() : c.column();
        if (e instanceof Ast.BinOp b)
            return astText(b.left()) + " " + b.op() + " " + astText(b.right());
        if (e instanceof Ast.UnaryOp u) return u.op() + astText(u.operand());
        if (e instanceof Ast.FuncCall f) {
            if (f.star()) return "COUNT(*)";
            return f.name() + "(" + astText(f.arg()) + ")";
        }
        if (e instanceof Ast.LikeOp l) return astText(l.operand()) + " LIKE '" + l.pattern() + "'";
        if (e instanceof Ast.BetweenOp b)
            return astText(b.operand()) + " BETWEEN " + astText(b.low()) + " AND " + astText(b.high());
        if (e instanceof Ast.IsNullOp i)
            return astText(i.operand()) + (i.negated() ? " IS NOT NULL" : " IS NULL");
        if (e instanceof Ast.InListOp in) {
            StringBuilder sb = new StringBuilder(astText(in.operand()))
                    .append(in.negated() ? " NOT IN (" : " IN (");
            for (int i = 0; i < in.items().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(astText(in.items().get(i)));
            }
            return sb.append(")").toString();
        }
        return e.getClass().getSimpleName();
    }

    // ---------------- Binder：子查询与外层列 ----------------

    /** 相关子查询的外层行上下文 */
    private static final class OuterCtx {
        final List<String> cols;
        Object[] row;

        OuterCtx(List<String> cols) {
            this.cols = cols;
        }
    }

    private final List<OuterCtx> ctxStack = new ArrayList<>();

    @Override
    public Expressions.EvalNode bindSubquery(Ast.Expr e, List<String> scope) {
        if (e instanceof Ast.InQueryOp q) {
            Expressions.EvalNode operand = Expressions.bind(q.operand(), scope, this);
            boolean neg = q.negated();
            if (!subqueryCorrelated(q.query(), scope)) {
                List<Object> vals = materializeColumn(q.query());
                return row -> {
                    Object v = operand.eval(row);
                    if (v == null) return Boolean.FALSE;
                    boolean found = containsValue(vals, v);
                    return neg ? !found : found;
                };
            }
            // 相关子查询：每行重放；lambda 直接持有 ctx（计划期弹栈后仍可写行值）
            OuterCtx[] ctxHolder = new OuterCtx[1];
            ExecOp plan = planWithOuter(q.query(), scope, ctxHolder);
            OuterCtx ctx = ctxHolder[0];
            return row -> {
                ctx.row = row;
                try {
                    plan.open();
                    Object[] r;
                    boolean found = false;
                    while ((r = plan.next()) != null) {
                        Object v = operand.eval(row);
                        if (v != null && containsValue(listOf(r), v)) {
                            found = true;
                            break;
                        }
                    }
                    return neg ? !found : found;
                } finally {
                    ExecOp.closeQuietly(plan);
                }
            };
        }
        if (e instanceof Ast.ExistsOp ex) {
            boolean neg = ex.negated();
            if (!subqueryCorrelated(ex.query(), scope)) {
                List<Object[]> rows = materializeRows(ex.query());
                return row -> neg == rows.isEmpty();
            }
            OuterCtx[] ctxHolder = new OuterCtx[1];
            ExecOp plan = planWithOuter(ex.query(), scope, ctxHolder);
            OuterCtx ctx = ctxHolder[0];
            return row -> {
                ctx.row = row;
                try {
                    plan.open();
                    boolean any = plan.next() != null;
                    return neg == !any; // negated: NOT EXISTS → any==false 时 true
                } finally {
                    ExecOp.closeQuietly(plan);
                }
            };
        }
        if (e instanceof Ast.ScalarQueryOp sq) {
            if (!subqueryCorrelated(sq.query(), scope)) {
                List<Object[]> rows = materializeRows(sq.query());
                Object scalar = rows.isEmpty() ? null
                        : rows.get(0).length == 0 ? null : rows.get(0)[0];
                if (rows.size() > 1)
                    throw new MiniDbException(MiniDbException.Code.EXEC, "标量子查询返回多行");
                return row -> scalar;
            }
            OuterCtx[] ctxHolder = new OuterCtx[1];
            ExecOp plan = planWithOuter(sq.query(), scope, ctxHolder);
            OuterCtx ctx = ctxHolder[0];
            return row -> {
                ctx.row = row;
                try {
                    plan.open();
                    Object[] r = plan.next();
                    if (r == null) return null;
                    if (plan.next() != null)
                        throw new MiniDbException(MiniDbException.Code.EXEC, "标量子查询返回多行");
                    return r.length == 0 ? null : r[0];
                } finally {
                    ExecOp.closeQuietly(plan);
                }
            };
        }
        throw new MiniDbException(MiniDbException.Code.EXEC, "未知子查询表达式");
    }

    private static boolean containsValue(List<Object> vals, Object v) {
        for (Object x : vals)
            if (x != null) {
                try {
                    if (Expressions.compare(x, v) == 0) return true;
                } catch (MiniDbException ignored) {
                    // 类型不匹配的值跳过
                }
            }
        return false;
    }

    private static List<Object> listOf(Object[] row) {
        List<Object> out = new ArrayList<>(row.length);
        for (Object o : row) out.add(o);
        return out;
    }

    @Override
    public Expressions.EvalNode lookupOuter(String table, String column) {
        for (int i = ctxStack.size() - 1; i >= 0; i--) {
            OuterCtx ctx = ctxStack.get(i);
            int idx = findColumn(ctx.cols, table, column);
            if (idx >= 0) {
                OuterCtx c = ctx;
                int fi = idx;
                return row -> c.row == null ? null : c.row[fi];
            }
        }
        return null;
    }

    private static int findColumn(List<String> cols, String table, String column) {
        if (table != null) {
            String want = table + "." + column;
            for (int i = 0; i < cols.size(); i++)
                if (cols.get(i).equalsIgnoreCase(want)) return i;
            return -1;
        }
        for (int i = 0; i < cols.size(); i++) {
            String n = cols.get(i);
            int dot = n.lastIndexOf('.');
            String bare = dot >= 0 ? n.substring(dot + 1) : n;
            if (bare.equalsIgnoreCase(column)) return i;
        }
        return -1;
    }

    /** 子查询是否引用了外层 scope 的列（粗略判定） */
    private boolean subqueryCorrelated(Ast.SelectStmt q, List<String> outerScope) {
        // 收集子查询 FROM 表的列全集
        Set<String> inner = new HashSet<>();
        flattenInnerColumns(q.from(), inner);
        List<Ast.ColRef> refs = new ArrayList<>();
        collectRefs(q, refs);
        for (Ast.ColRef c : refs) {
            if (c.table() != null) {
                boolean inInner = inner.stream().anyMatch(n -> {
                    int dot = n.indexOf('.');
                    return dot > 0 && n.substring(0, dot).equalsIgnoreCase(c.table());
                });
                if (!inInner) return true;
            } else if (findColumn(inner.stream().toList(), null, c.column()) < 0) {
                return true;
            }
        }
        return false;
    }

    private void flattenInnerColumns(Ast.TableRef ref, Set<String> out) {
        if (ref == null) return;
        if (ref instanceof Ast.NamedTable nt) {
            Table t = db.getTable(nt.name());
            String alias = nt.alias() != null ? nt.alias() : nt.name();
            for (var c : t.schema().columns()) out.add(alias + "." + c.name());
        } else if (ref instanceof Ast.Join j) {
            flattenInnerColumns(j.left(), out);
            flattenInnerColumns(j.right(), out);
        }
    }

    private void collectRefs(Ast.SelectStmt q, List<Ast.ColRef> out) {
        for (Ast.SelectItem item : q.items()) Expressions.collectColumns(item.expr(), out);
        Expressions.collectColumns(q.where(), out);
        if (q.groupBy() != null) for (Ast.Expr e : q.groupBy()) Expressions.collectColumns(e, out);
        Expressions.collectColumns(q.having(), out);
        if (q.orderBy() != null)
            for (Ast.OrderItem oi : q.orderBy()) Expressions.collectColumns(oi.expr(), out);
    }

    private List<Object> materializeColumn(Ast.SelectStmt q) {
        Result r = runSelect(stripOrderLimit(q));
        List<Object> out = new ArrayList<>();
        for (Object[] row : r.rows()) out.add(row.length == 0 ? null : row[0]);
        return out;
    }

    private List<Object[]> materializeRows(Ast.SelectStmt q) {
        return runSelect(stripOrderLimit(q)).rows();
    }

    /** 子查询去 ORDER BY/LIMIT（物化场景无意义），保留副本避免改 AST */
    private Ast.SelectStmt stripOrderLimit(Ast.SelectStmt q) {
        return new Ast.SelectStmt(q.distinct(), q.items(), q.from(), q.where(),
                q.groupBy(), q.having(), null, null, null);
    }

    /** 计划一个相关子查询：其列可解析到外层 scope（通过 ctxStack）；创建的 ctx 经 holder 交回。 */
    private ExecOp planWithOuter(Ast.SelectStmt q, List<String> outerScope, OuterCtx[] holder) {
        OuterCtx ctx = new OuterCtx(outerScope);
        holder[0] = ctx;
        ctxStack.add(ctx);
        try {
            return planSelect(q);
        } finally {
            ctxStack.remove(ctxStack.size() - 1);
        }
    }

    private static Ast.SelectStmt stripOrderLimitKeepLimit(Ast.SelectStmt q) {
        return q; // 相关子查询完整保留（绑定期间外层列经 ctxStack 解析）
    }
}
