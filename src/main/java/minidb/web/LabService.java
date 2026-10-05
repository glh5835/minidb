package minidb.web;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.exec.Executor;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Table;
import minidb.txn.TxnSession;
import minidb.wal.Recovery;
import minidb.web.session.DatabaseRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内核实验室（计划书 §20）：每个实验都在独立实验库（*.exp.db）上运行真实内核，
 * 绝不伪造结果。工作库与实验库物理隔离（计划书 §2.3）。
 */
public final class LabService {
    private final DatabaseRegistry registry;
    private final Path labDir;

    public LabService(DatabaseRegistry registry, Path dataDir) {
        this.registry = registry;
        this.labDir = dataDir.toAbsolutePath().normalize().resolve(".minidb-lab");
        try {
            Files.createDirectories(labDir);
        } catch (Exception e) {
            throw new MiniDbException(MiniDbException.Code.IO, "无法创建实验目录: " + labDir, e);
        }
    }

    private Path labFile(String name) {
        return labDir.resolve(name + ".exp.db");
    }

    private static Api.ApiException labError(String msg) {
        return new Api.ApiException("EXPERIMENT_FAILED", msg, 500);
    }

    // ================= 双会话事务实验 =================

    /** 事务实验：两个真实会话（各自 Executor 内的 TxnSession，null = 自动提交）+ 异步等待操作。 */
    public static final class TxnLab implements AutoCloseable {
        public final String dbPath;
        public final DatabaseRegistry.Entry entry;
        public final Executor executor;
        public volatile TxnSession a;
        public volatile TxnSession b;
        public volatile Future<Map<String, Object>> pendingA;
        public volatile Future<Map<String, Object>> pendingB;
        private final ExecutorService async = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "minidb-lab-txn");
            t.setDaemon(true);
            return t;
        });

        TxnLab(String dbPath, DatabaseRegistry.Entry entry) {
            this.dbPath = dbPath;
            this.entry = entry;
            this.executor = new Executor(entry.db);
        }

        TxnSession side(String s) {
            TxnSession t = "B".equalsIgnoreCase(s) ? b : a;
            if (t == null || t.finished)
                throw new MiniDbException(MiniDbException.Code.TXN, "会话 " + s + " 没有活动事务（先 BEGIN）");
            return t;
        }

        void setSide(String s, TxnSession t) {
            if ("B".equalsIgnoreCase(s)) b = t;
            else a = t;
        }

        Future<Map<String, Object>> pending(String s) {
            return "B".equalsIgnoreCase(s) ? pendingB : pendingA;
        }

        void setPending(String s, Future<Map<String, Object>> f) {
            if ("B".equalsIgnoreCase(s)) pendingB = f;
            else pendingA = f;
        }

        @Override
        public void close() {
            async.shutdownNow();
            for (TxnSession t : List.of(a, b)) {
                try {
                    if (t != null && !t.finished) executor.rollback(t);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private final Map<String, TxnLab> txnLabs = new ConcurrentHashMap<>();

    /** 重建事务实验库（account 表，两行余额）。 */
    public Map<String, Object> txnSetup() {
        TxnLab old = txnLabs.remove("txn");
        if (old != null) {
            old.close();
            registry.release(old.entry);
        }
        DatabaseRegistry.Entry e = resetLabFile("txn");
        TxnLab lab;
        try {
            e.db.createTable("account", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("name", ColumnType.VARCHAR, 50),
                    new Column("balance", ColumnType.INT, 0)));
            Executor ex = new Executor(e.db);
            StringBuilder seed = new StringBuilder("INSERT INTO account VALUES (1, 'Alice', 100), (2, 'Bob', 200)");
            for (int i = 3; i <= 50; i++)
                seed.append(", (").append(i).append(", 'user").append(i).append("', ").append(i * 10).append(')');
            ex.execute(seed.toString());
            // 行级锁实验需要点查走 IndexScan（只锁命中行），故建索引
            ex.execute("CREATE INDEX idx_account_id ON account(id)");
            lab = new TxnLab(e.path.toString(), e);
            txnLabs.put("txn", lab);
        } catch (RuntimeException ex) {
            registry.release(e);
            throw ex;
        }
        return txnState();
    }

    /** 实验库文件重置（关闭残留实例 → 删除文件 → 新建）。 */
    private DatabaseRegistry.Entry resetLabFile(String name) {
        Path file = labFile(name);
        try {
            if (Files.exists(file)) {
                Database oneOff = Database.open(file);
                oneOff.close();
                Files.delete(file);
            }
            Files.deleteIfExists(labDir.resolve(name + ".exp.db.wal"));
        } catch (Exception e) {
            throw labError("无法重置实验库 " + name + ": " + e.getMessage());
        }
        return registry.acquire(file.toString(), true);
    }

    /** 实验步骤：BEGIN / SQL / COMMIT / ROLLBACK。锁等待场景转异步挂起。 */
    public Map<String, Object> txnExec(Map<String, Object> body) {
        TxnLab lab = requireLab();
        String side = Json.str(body, "session", "A").toUpperCase();
        if (!side.equals("A") && !side.equals("B"))
            throw new Api.ApiException("VALIDATION_ERROR", "实验会话只能是 A 或 B: " + side);
        String action = Json.str(body, "action", "SQL").toUpperCase();
        long t0 = System.nanoTime();
        try {
            switch (action) {
                case "BEGIN" -> {
                    TxnSession cur = "B".equals(side) ? lab.b : lab.a;
                    if (cur != null && !cur.finished)
                        throw new MiniDbException(MiniDbException.Code.TXN, "事务已在进行中");
                    TxnSession fresh = lab.executor.begin(isolationOf(body, TxnSession.Isolation.REPEATABLE_READ));
                    lab.setSide(side, fresh);
                    return stepResult(side, action, "BEGIN（隔离级别 " + fresh.isolation + "）", t0);
                }
                case "COMMIT" -> {
                    TxnSession s = lab.side(side);
                    lab.executor.commit(s);
                    return stepResult(side, action, "COMMIT", t0);
                }
                case "ROLLBACK" -> {
                    TxnSession s = lab.side(side);
                    lab.executor.rollback(s);
                    return stepResult(side, action, "ROLLBACK", t0);
                }
                case "SQL" -> {
                    String sql = Json.str(body, "sql", "").trim();
                    if (sql.isEmpty()) throw new Api.ApiException("VALIDATION_ERROR", "缺少 sql");
                    String upper = sql.toUpperCase();
                    if (upper.startsWith("BEGIN") || upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK"))
                        throw new Api.ApiException("VALIDATION_ERROR",
                                "实验步骤请使用 action=BEGIN/COMMIT/ROLLBACK，不要混用 SQL 文本事务语句");
                    return execLabSql(lab, side, sql, t0);
                }
                default -> throw new Api.ApiException("VALIDATION_ERROR", "未知实验动作: " + action);
            }
        } catch (MiniDbException e) {
            Api.ApiException ae = Api.map(e);
            Map<String, Object> r = stepResult(side, action, null, t0);
            r.put("success", false);
            r.put("error", Map.of("code", ae.code, "message", ae.getMessage()));
            return r;
        }
    }

    private TxnSession.Isolation isolationOf(Map<String, Object> body, TxnSession.Isolation def) {
        String iso = Json.str(body, "isolation", def.name());
        return "READ_COMMITTED".equalsIgnoreCase(iso) || "RC".equalsIgnoreCase(iso)
                ? TxnSession.Isolation.READ_COMMITTED : TxnSession.Isolation.REPEATABLE_READ;
    }

    /** SQL 步骤：锁可能阻塞 → 300ms 内未完成转异步挂起，前端轮询真实等待状态。 */
    private Map<String, Object> execLabSql(TxnLab lab, String side, String sql, long t0) {
        TxnSession s = lab.side(side);
        Future<Map<String, Object>> f = asyncSubmit(lab, side, s, sql);
        lab.setPending(side, f);
        try {
            return f.get(300, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("pending", true);
            r.put("session", side);
            r.put("note", "语句正在等待锁（该步骤在后台线程真实执行）。请观察锁等待图，或提交/回滚对方事务。");
            return r;
        } catch (Exception e) {
            throw labError("实验执行失败: " + e.getMessage());
        }
    }

    private Future<Map<String, Object>> asyncSubmit(TxnLab lab, String side, TxnSession s, String sql) {
        return lab.async.submit(() -> {
            long t1 = System.nanoTime();
            try {
                Object stmt = minidb.sql.Parser.parse(sql);
                if (stmt instanceof minidb.sql.Ast.SelectStmt sel) {
                    Executor.Result r = lab.executor.runSelect(sel, s, MiniDbService.MAX_RESULT_ROWS);
                    Map<String, Object> r2 = stepResult(side, sql, null, t1);
                    r2.put("columns", r.columns());
                    List<Object> rows = new ArrayList<>();
                    for (Object[] row : r.rows()) {
                        List<Object> j = new ArrayList<>(row.length);
                        for (Object v : row) j.add(Json.toJsonValue(v));
                        rows.add(j);
                    }
                    r2.put("rows", rows);
                    r2.put("rowCount", r.rows().size());
                    return r2;
                }
                Executor.Result r = lab.executor.execute(stmt, s);
                return stepResult(side, sql, r.message(), t1);
            } catch (MiniDbException e) {
                Api.ApiException ae = Api.map(e);
                Map<String, Object> r = stepResult(side, sql, null, t1);
                r.put("success", false);
                r.put("error", Map.of("code", ae.code, "message", ae.getMessage()));
                return r;
            }
        });
    }

    /** 轮询挂起的实验操作（锁等待图变化后取结果）。 */
    public Map<String, Object> txnPoll() {
        TxnLab lab = requireLab();
        Map<String, Object> data = new LinkedHashMap<>();
        for (String side : List.of("A", "B")) {
            Future<Map<String, Object>> f = lab.pending(side);
            Map<String, Object> st = new LinkedHashMap<>();
            if (f == null) {
                st.put("state", "idle");
            } else if (f.isDone()) {
                try {
                    st.put("state", "done");
                    st.put("result", f.get());
                } catch (Exception e) {
                    st.put("state", "error");
                    st.put("error", String.valueOf(e.getMessage()));
                }
                lab.setPending(side, null);
            } else {
                st.put("state", "waiting");
            }
            data.put(side, st);
        }
        return data;
    }

    public Map<String, Object> txnState() {
        TxnLab lab = requireLab();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessions", Map.of(
                "A", labSideState(lab, "A", lab.a),
                "B", labSideState(lab, "B", lab.b)));
        List<Map<String, Object>> locks = new ArrayList<>();
        for (var l : lab.entry.db.lockManager().snapshot()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.key());
            m.put("holders", l.holders());
            m.put("mode", l.mode());
            m.put("waiting", l.waiting());
            locks.add(m);
        }
        data.put("locks", locks);
        // 实时数据视图：无锁读（session=null 不加行锁），可能包含未提交修改——
        // 这正是实验要观察的；提交/回滚后的持久状态由 liveRows 的变化直接呈现。
        Executor.Result r;
        try {
            r = lab.executor.runSelect((minidb.sql.Ast.SelectStmt)
                    minidb.sql.Parser.parse("SELECT * FROM account ORDER BY id"), null);
        } catch (MiniDbException e) {
            throw Api.map(e);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        var schema = lab.entry.db.getTable("account").schema();
        for (Object[] row : r.rows()) rows.add(Json.rowToMap(schema, row, null));
        data.put("liveRows", rows);
        data.put("columns", r.columns());
        data.put("liveRowsNote", "实时视图（无锁读，可能包含未提交修改）");
        return data;
    }

    private Map<String, Object> labSideState(TxnLab lab, String side, TxnSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", s != null && !s.finished);
        m.put("isolation", s != null && !s.finished ? s.isolation.name() : "AUTO_COMMIT");
        if (s != null && !s.finished) {
            m.put("txnId", String.valueOf(s.txnId));
            m.put("heldLocks", new ArrayList<>(s.heldLocks));
        }
        Future<?> f = lab.pending(side);
        m.put("pending", f != null && !f.isDone());
        return m;
    }

    private Map<String, Object> stepResult(String side, String action, String message, long t0) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("session", side);
        r.put("action", action);
        if (message != null) r.put("message", message);
        r.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        return r;
    }

    private TxnLab requireLab() {
        TxnLab lab = txnLabs.get("txn");
        if (lab == null)
            throw new Api.ApiException("EXPERIMENT_NOT_READY", "请先初始化双会话事务实验环境。", 409);
        return lab;
    }

    // ================= WAL 与崩溃恢复实验 =================

    /**
     * 标准流程（计划书 §26，全部真实内核）：
     * 实验库 → T1 提交 / T2 不提交 → 读 WAL 原文 → db.crash() 断电 →
     * 重新打开触发真实 Recovery（redo+undo，捕获报告）→ 展示 → 一致性检查。
     */
    public Map<String, Object> recoveryRun() {
        DatabaseRegistry.Entry e = resetLabFile("recovery");
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Database db = e.db;
            db.createTable("account", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("name", ColumnType.VARCHAR, 50),
                    new Column("balance", ColumnType.INT, 0)));
            Executor ex = new Executor(db);
            TxnSession t1 = ex.begin(TxnSession.Isolation.REPEATABLE_READ);
            ex.execute("INSERT INTO account VALUES (1, 'Alice', 100)", t1);
            ex.execute("INSERT INTO account VALUES (2, 'Bob', 200)", t1);
            ex.commit(t1);
            TxnSession t2 = ex.begin(TxnSession.Isolation.REPEATABLE_READ);
            ex.execute("INSERT INTO account VALUES (3, 'Carol', 300)", t2);
            ex.execute("UPDATE account SET balance = 999 WHERE id = 1", t2);
            // 崩溃前：读 WAL 原文（真实 readAll）
            data.put("walRecords", walView(db));
            data.put("beforeCrash", List.of(
                    "T1（提交）：INSERT Alice 100、INSERT Bob 200、COMMIT（COMMIT 记录已 fsync）",
                    "T2（未提交）：INSERT Carol 300、UPDATE Alice balance→999"));
            long bufferPoolDirty = db.engine().pool().snapshot().stream()
                    .filter(p -> p.dirty()).count();
            data.put("dirtyPagesAtCrash", bufferPoolDirty);
        } finally {
            e.db.crash(); // 模拟断电：缓冲池不刷盘、日志不 fsync 不截断
            registry.discardEntry(e); // 崩溃后句柄作废，从注册表移除（不再触发 close）
        }
        // 重新打开：构造器自动执行真实 Recovery
        DatabaseRegistry.Entry reopened = registry.acquire(e.path.toString(), false);
        try {
            Database db2 = reopened.db;
            Recovery.RecoveryReport report = db2.recoveryReport();
            List<Map<String, Object>> entries = new ArrayList<>();
            if (report != null) {
                for (var en : report.entries()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lsn", en.lsn() > 0 ? String.valueOf(en.lsn()) : null);
                    m.put("txnId", String.valueOf(en.txnId()));
                    m.put("phase", en.phase());
                    m.put("action", en.action());
                    m.put("table", en.table());
                    m.put("rid", en.pageId() >= 0 ? en.pageId() + "/" + en.slot() : null);
                    m.put("applied", en.applied());
                    m.put("detail", en.detail());
                    entries.add(m);
                }
            }
            data.put("recoveryReport", entries);
            data.put("committedTxns", report == null ? List.of() :
                    report.committedTxns().stream().map(String::valueOf).toList());
            data.put("pendingTxns", report == null ? List.of() :
                    report.pendingTxns().stream().map(String::valueOf).toList());
            // 恢复后数据
            Table t = db2.getTable("account");
            List<Map<String, Object>> rows = new ArrayList<>();
            var it = t.scan();
            while (it.hasNext()) rows.add(Json.rowToMap(t.schema(), it.next().values(), null));
            data.put("rowsAfterRecovery", rows);
            // 一致性检查（真实数据断言）
            List<Map<String, Object>> checks = new ArrayList<>();
            checks.add(check("已提交事务 T1 的两行都在（redo 保证持久性）",
                    containsRow(rows, "Alice") && containsRow(rows, "Bob")));
            checks.add(check("未提交事务 T2 的新行被撤销（undo）", !containsRow(rows, "Carol")));
            checks.add(check("未提交的 UPDATE 被撤销：Alice 余额仍为 100", balanceOf(rows, "Alice") == 100));
            checks.add(check("Bob 余额仍为 200", balanceOf(rows, "Bob") == 200));
            data.put("consistencyChecks", checks);
        } finally {
            reopened.db.flush(); // 实验结果固化（文件保留在磁盘供查看，注册表引用释放避免文件锁）
            registry.release(reopened);
        }
        return data;
    }

    private List<Map<String, Object>> walView(Database db) {
        List<Map<String, Object>> wal = new ArrayList<>();
        for (var r : db.wal().readAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lsn", String.valueOf(r.lsn()));
            m.put("type", walType(r.type()));
            m.put("txnId", String.valueOf(r.txnId()));
            m.put("table", r.table());
            m.put("rid", r.pageId() >= 0 ? r.pageId() + "/" + r.slot() : null);
            wal.add(m);
        }
        return wal;
    }

    private static String walType(byte t) {
        return switch (t) {
            case minidb.wal.WalLog.BEGIN -> "BEGIN";
            case minidb.wal.WalLog.INSERT -> "INSERT";
            case minidb.wal.WalLog.DELETE -> "DELETE";
            case minidb.wal.WalLog.UPDATE -> "UPDATE";
            case minidb.wal.WalLog.COMMIT -> "COMMIT";
            case minidb.wal.WalLog.ABORT -> "ABORT";
            default -> "TYPE" + t;
        };
    }

    private Map<String, Object> check(String name, boolean pass) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pass", pass);
        return m;
    }

    private static boolean containsRow(List<Map<String, Object>> rows, String name) {
        for (Map<String, Object> r : rows)
            if (name.equals(r.get("name"))) return true;
        return false;
    }

    private static long balanceOf(List<Map<String, Object>> rows, String name) {
        for (Map<String, Object> r : rows)
            if (name.equals(r.get("name"))) {
                Object v = r.get("balance");
                return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
            }
        return Long.MIN_VALUE;
    }

    // ================= 存储与缓冲池实验 =================

    /** 存储实验库：定长表 + 变长表，观察页/slot/RID。 */
    public Map<String, Object> storageSetup() {
        DatabaseRegistry.Entry e = resetLabFile("storage");
        try {
            e.db.createTable("fixed_demo", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("num", ColumnType.BIGINT, 0),
                    new Column("score", ColumnType.DOUBLE, 0)));
            e.db.createTable("var_demo", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("note", ColumnType.VARCHAR, 200)));
            Executor ex = new Executor(e.db);
            ex.execute("INSERT INTO fixed_demo VALUES (1, 100, 1.5), (2, 200, 2.5)");
            ex.execute("INSERT INTO var_demo VALUES (1, '短记录'), (2, 'a longer record text...')");
        } catch (RuntimeException ex) {
            registry.release(e);
            throw ex;
        }
        // 实验库落地后释放（后续 state/act 按需重开，数据已 checkpoint）
        registry.release(e);
        return storageState("fixed_demo");
    }

    private DatabaseRegistry.Entry storageLab() {
        Path file = labFile("storage");
        if (!Files.exists(file))
            throw new Api.ApiException("EXPERIMENT_NOT_READY", "请先初始化存储实验环境。", 409);
        return registry.acquire(file.toString(), false);
    }

    public Map<String, Object> storageState(String table) {
        DatabaseRegistry.Entry e = storageLab();
        try {
            Database db = e.db;
            Table t = db.getTable(table == null || table.isBlank() ? "fixed_demo" : table);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dbFile", e.path.toString());
            data.put("pageSize", minidb.storage.Page.SIZE);
            data.put("rowCount", t.rowCount());
            data.put("pageCount", t.pageCount());
            data.put("totalFreeBytes", t.totalFreeBytes());
            data.put("fixedLength", t.schema().fixedLength());
            data.put("diskPages", db.engine().pageCount());
            var pool = db.engine().pool();
            data.put("bufferPool", Map.of(
                    "capacity", pool.capacity(), "size", pool.size(),
                    "hits", pool.hits(), "misses", pool.misses(),
                    "evictions", pool.evictions(), "writebacks", pool.writebacks()));
            java.util.TreeSet<Integer> pageIds = new java.util.TreeSet<>();
            var it = t.scan();
            List<Map<String, Object>> rowList = new ArrayList<>();
            while (it.hasNext()) {
                var row = it.next();
                pageIds.add(row.rid().pageId());
                rowList.add(Json.rowToMap(t.schema(), row.values(), Json.ridToString(row.rid())));
            }
            List<Map<String, Object>> pages = new ArrayList<>();
            for (int pid : pageIds) {
                pages.add(db.engine().pool().withPage(pid, false, p -> pageView(p, pid)));
            }
            data.put("pages", pages);
            data.put("rows", rowList);
            return data;
        } finally {
            registry.release(e);
        }
    }

    private Map<String, Object> pageView(minidb.storage.Page p, int pid) {
        byte[] b = p.data();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pageId", pid);
        m.put("type", p.typeOrNull() == null ? "META" : p.typeOrNull().name());
        m.put("numSlots", minidb.storage.TablePageHeader.numSlots(b));
        m.put("freeLow", minidb.storage.TablePageHeader.freeLow(b) & 0xFFFF);
        m.put("dataStart", minidb.storage.TablePageHeader.dataStart(b) & 0xFFFF);
        m.put("totalFree", minidb.storage.TablePageHeader.totalFree(b));
        m.put("next", minidb.storage.TablePageHeader.nextPage(b));
        m.put("prev", minidb.storage.TablePageHeader.prevPage(b));
        long lsn = p.pageLsn();
        m.put("pageLsn", lsn > 0 ? String.valueOf(lsn) : null);
        m.put("usedBytes", minidb.storage.Page.SIZE - minidb.storage.TablePageHeader.totalFree(b));
        return m;
    }

    /** 存储实验动作：插入/删除记录后直接观察页面变化。 */
    public Map<String, Object> storageAct(Map<String, Object> body) {
        DatabaseRegistry.Entry e = storageLab();
        try {
            Database db = e.db;
            Executor ex = new Executor(db);
            String table = Json.str(body, "table", "fixed_demo");
            String op = Json.str(body, "op", "insert");
            Map<String, Object> result = new LinkedHashMap<>();
            switch (op) {
                case "insertFixed" -> result.put("result",
                        ex.execute("INSERT INTO fixed_demo VALUES ("
                                + Json.intVal(body.get("id"), 0) + ", "
                                + Json.intVal(body.get("num"), 0) + ", "
                                + Json.intVal(body.get("score"), 0) + ".0)").message());
                case "insertVar" -> {
                    String note = Json.str(body, "note", "记录" + System.currentTimeMillis() % 1000);
                    result.put("result", ex.execute(
                            "INSERT INTO var_demo VALUES (" + Json.intVal(body.get("id"), 0)
                                    + ", '" + note.replace("'", "''") + "')").message());
                }
                case "delete" -> {
                    Object ridObj = body.get("rid");
                    if (!(ridObj instanceof String ridStr))
                        throw new Api.ApiException("VALIDATION_ERROR", "缺少 rid");
                    Rid rid = Json.ridFromString(ridStr);
                    ex.deleteRowByRid(table, rid, null);
                    result.put("result", "已删除 " + ridStr);
                }
                default -> throw new Api.ApiException("VALIDATION_ERROR", "未知操作: " + op);
            }
            result.put("state", storageState(table));
            return result;
        } finally {
            registry.release(e);
        }
    }

    /** 缓冲池实验需要跨请求的持久实例（命中率/写回计数才连续）。 */
    private DatabaseRegistry.Entry bpEntry;

    private DatabaseRegistry.Entry bpLab() {
        if (bpEntry == null) {
            Path file = labFile("bufferpool");
            if (!Files.exists(file))
                throw new Api.ApiException("EXPERIMENT_NOT_READY", "请先初始化缓冲池实验环境。", 409);
            bpEntry = registry.acquire(file.toString(), false);
        }
        return bpEntry;
    }

    /** 缓冲池实验库 + 动作。 */
    public Map<String, Object> bufferPoolSetup() {
        if (bpEntry != null) {
            registry.release(bpEntry);
            bpEntry = null;
        }
        DatabaseRegistry.Entry e = resetLabFile("bufferpool");
        try {
            e.db.createTable("bp_demo", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("val", ColumnType.VARCHAR, 100)));
            Executor ex = new Executor(e.db);
            StringBuilder sb = new StringBuilder("INSERT INTO bp_demo VALUES ");
            for (int i = 1; i <= 50; i++) {
                if (i > 1) sb.append(", ");
                sb.append("(").append(i).append(", 'value-").append(i).append("')");
            }
            ex.execute(sb.toString());
            ex.execute("CREATE INDEX idx_bp_id ON bp_demo(id)");
        } catch (RuntimeException ex) {
            registry.release(e);
            throw ex;
        }
        bpEntry = e; // 保留持久实例：计数器跨请求连续（服务器关闭时由 registry.close 收口）
        return bufferPoolState(null);
    }

    public Map<String, Object> bufferPoolState(String action) {
        DatabaseRegistry.Entry entry = bpLab();
        Database db = entry.db;
        var pool = db.engine().pool();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dbFile", entry.path.toString());
        data.put("capacity", pool.capacity());
        data.put("size", pool.size());
        data.put("hits", pool.hits());
        data.put("misses", pool.misses());
        data.put("evictions", pool.evictions());
        data.put("writebacks", pool.writebacks());
        data.put("diskPages", db.engine().pageCount());
        data.put("dirtyPages", pool.snapshot().stream().filter(pp -> pp.dirty()).count());
        List<Map<String, Object>> pages = new ArrayList<>();
        for (var p : pool.snapshot()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageId", p.pageId());
            m.put("pin", p.pinCount());
            m.put("dirty", p.dirty());
            m.put("pageLsn", p.pageLsn() > 0 ? String.valueOf(p.pageLsn()) : null);
            m.put("type", p.type());
            pages.add(m);
        }
        data.put("pages", pages);
        if (action != null) data.put("lastAction", action);
        return data;
    }

    /** 缓冲池实验动作：读（命中/缺失）、写（变脏）、flush（写回）。 */
    public Map<String, Object> bufferPoolAct(Map<String, Object> body) {
        String op = Json.str(body, "op", "read");
        Database db = bpLab().db;
        Executor ex = new Executor(db);
        String action;
        switch (op) {
            case "readAll" -> {
                action = "SELECT * FROM bp_demo（全表扫描：按需加载页，观察 miss/hit 与淘汰）";
                ex.execute("SELECT * FROM bp_demo");
            }
            case "readOne" -> {
                action = "索引点查 id=" + Json.intVal(body.get("id"), 1)
                        + "（只加载 B+ 树路径与数据页）";
                ex.execute("SELECT * FROM bp_demo WHERE id = " + Json.intVal(body.get("id"), 1));
            }
            case "update" -> {
                int id = Json.intVal(body.get("id"), 1);
                action = "UPDATE bp_demo SET val='dirty-" + id + "' WHERE id=" + id
                        + "（命中页变脏，等待写回）";
                ex.execute("UPDATE bp_demo SET val = 'dirty-" + id + "' WHERE id = " + id);
            }
            case "flush" -> {
                action = "flushAll（脏页按 WAL 先写原则落盘，观察 writebacks 增加）";
                db.flush();
            }
            default -> throw new Api.ApiException("VALIDATION_ERROR", "未知操作: " + op);
        }
        return bufferPoolState(action);
    }

    // ================= 性能实验 =================

    public Map<String, Object> perfRun(Map<String, Object> body) {
        int rows = Math.min(Math.max(Json.intVal(body.get("rows"), 10000), 100), 200000);
        int queries = Math.min(Math.max(Json.intVal(body.get("queries"), 50), 1), 1000);
        int warmup = Math.min(Json.intVal(body.get("warmup"), 5), 50);
        DatabaseRegistry.Entry e = resetLabFile("perf");
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Database db = e.db;
            db.createTable("perf_t", List.of(
                    new Column("id", ColumnType.INT, 0),
                    new Column("val", ColumnType.VARCHAR, 50)));
            Executor ex = new Executor(db);
            // 单事务批量插入（fsync 只在提交时发生）
            TxnSession load = ex.begin(TxnSession.Isolation.READ_COMMITTED);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= rows; i++) {
                sb.setLength(0);
                sb.append("INSERT INTO perf_t VALUES (").append(i).append(", 'row-").append(i).append("')");
                ex.execute(sb.toString(), load);
            }
            ex.commit(load);
            data.put("rowsLoaded", rows);
            // 无索引基线：预热 + 计时（真实执行）
            long seqTotal = 0;
            for (int i = 0; i < warmup; i++)
                ex.execute("SELECT * FROM perf_t WHERE id = " + (i + 1));
            List<Long> seqSamples = new ArrayList<>();
            for (int i = 0; i < queries; i++) {
                int id = 1 + (int) (Math.random() * rows);
                long t0 = System.nanoTime();
                ex.execute("SELECT * FROM perf_t WHERE id = " + id);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                seqSamples.add(ms);
                seqTotal += ms;
            }
            data.put("planSeqScan", ex.explain("SELECT * FROM perf_t WHERE id = 1"));
            // 建索引
            ex.execute("CREATE INDEX idx_perf_id ON perf_t(id)");
            for (int i = 0; i < warmup; i++)
                ex.execute("SELECT * FROM perf_t WHERE id = " + (i + 1));
            long idxTotal = 0;
            List<Long> idxSamples = new ArrayList<>();
            for (int i = 0; i < queries; i++) {
                int id = 1 + (int) (Math.random() * rows);
                long t0 = System.nanoTime();
                ex.execute("SELECT * FROM perf_t WHERE id = " + id);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                idxSamples.add(ms);
                idxTotal += ms;
            }
            // 执行计划对比（真实 Optimizer：建索引前 SeqScan，建索引后 IndexScan）
            data.put("planIndexScan", ex.explain("SELECT * FROM perf_t WHERE id = 1"));
            Map<String, Object> seq = new LinkedHashMap<>();
            seq.put("mode", "SeqScan（无索引）");
            seq.put("avgMs", round1((double) seqTotal / queries));
            seq.put("totalMs", seqTotal);
            seq.put("maxMs", seqSamples.stream().max(Long::compare).orElse(0L));
            Map<String, Object> idx = new LinkedHashMap<>();
            idx.put("mode", "IndexScan（B+ 树索引 idx_perf_id）");
            idx.put("avgMs", round1((double) idxTotal / queries));
            idx.put("totalMs", idxTotal);
            idx.put("maxMs", idxSamples.stream().max(Long::compare).orElse(0L));
            data.put("results", List.of(seq, idx));
            data.put("environment", Map.of(
                    "rows", rows, "queries", queries, "warmup", warmup,
                    "sql", "SELECT * FROM perf_t WHERE id = ?（随机 id 点查）",
                    "ranAt", java.time.LocalDateTime.now().toString(),
                    "note", "本次为实时测量结果；PERF.md 中的数据为历史测试结果"));
        } finally {
            registry.release(e); // 归零时物理关闭（checkpoint）
        }
        return data;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ================= JDBC 实验 =================

    /** 真实 JDBC 流程（MiniDbDriver），独立实验库，输出代码 + 日志 + 数据库变化。 */
    public Map<String, Object> jdbcRun() {
        // JDBC 驱动会自己打开 Database 实例（MiniDbConnection → Database.open），
        // 因此该实验文件绝不进注册表，保证"同路径单实例"。
        Path file = labFile("jdbc");
        try {
            if (Files.exists(file)) {
                Files.delete(file);
            }
            Files.deleteIfExists(labDir.resolve("jdbc.exp.db.wal"));
        } catch (Exception e) {
            throw labError("无法重置 JDBC 实验库: " + e.getMessage());
        }
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> finalRows = new ArrayList<>();
        try (var conn = new minidb.jdbc.MiniDbDriver().connect(
                "jdbc:minidb:" + file, new java.util.Properties())) {
            log.add("Connection conn = DriverManager.getConnection(\"jdbc:minidb:jdbc.exp.db\")");
            log.add("// autoCommit = " + conn.getAutoCommit());
            // 1. Statement DDL
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE user (id INT, name VARCHAR(30), score DOUBLE)");
                log.add("st.execute(\"CREATE TABLE user (...)\")  → 建表成功");
            }
            // 2. PreparedStatement 批量插入
            try (var ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
                String[] names = {"张三", "李四", "王五"};
                for (int i = 0; i < 3; i++) {
                    ps.setInt(1, i + 1);
                    ps.setString(2, names[i]);
                    ps.setDouble(3, 90.0 - i * 5);
                    ps.executeUpdate();
                }
                log.add("PreparedStatement: INSERT INTO user VALUES (?,?,?)  ×3 → 3 行已插入");
            }
            // 3. ResultSet 查询
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT id, name, score FROM user ORDER BY id")) {
                while (rs.next())
                    log.add("ResultSet 遍历: " + rs.getInt("id") + " | "
                            + rs.getString("name") + " | " + rs.getDouble("score"));
            }
            // 4. 事务：回滚演示
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.executeUpdate("UPDATE user SET score = 0 WHERE id = 1");
                log.add("conn.setAutoCommit(false); UPDATE score→0（未提交）");
            }
            conn.rollback();
            log.add("conn.rollback() → 数据恢复（查询验证 score=" + queryScore(conn, 1) + "）");
            // 5. 事务：提交演示
            try (var ps = conn.prepareStatement("UPDATE user SET score = ? WHERE name = ?")) {
                ps.setDouble(1, 99.5);
                ps.setString(2, "李四");
                ps.executeUpdate();
            }
            conn.commit();
            log.add("UPDATE score→99.5 WHERE name='李四'; conn.commit() → 提交生效");
            conn.setAutoCommit(true);
            // 6. DELETE
            try (var st = conn.createStatement()) {
                int n = st.executeUpdate("DELETE FROM user WHERE id = 3");
                log.add("DELETE FROM user WHERE id = 3 → " + n + " 行");
            }
            // 最终数据
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT id, name, score FROM user ORDER BY id")) {
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++)
                        row.put(meta.getColumnName(c), Json.toJsonValue(rs.getObject(c)));
                    finalRows.add(row);
                }
            }
        } catch (Exception ex) {
            throw labError("JDBC 实验失败: " + ex.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("log", log);
        data.put("rows", finalRows);
        data.put("code", JDBC_CODE_SAMPLE);
        return data;
    }

    private static double queryScore(java.sql.Connection conn, int id) throws Exception {
        try (var ps = conn.prepareStatement("SELECT score FROM user WHERE id = ?")) {
            ps.setInt(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : -1;
            }
        }
    }

    private static final String JDBC_CODE_SAMPLE = """
            Connection conn = DriverManager.getConnection("jdbc:minidb:jdbc.exp.db");
            Statement st = conn.createStatement();
            st.execute("CREATE TABLE user (id INT, name VARCHAR(30), score DOUBLE)");

            PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)");
            ps.setInt(1, 1); ps.setString(2, "张三"); ps.setDouble(3, 90.0);
            ps.executeUpdate();

            ResultSet rs = st.executeQuery("SELECT id, name, score FROM user ORDER BY id");
            while (rs.next()) { ... }

            conn.setAutoCommit(false);
            st.executeUpdate("UPDATE user SET score = 0 WHERE id = 1");
            conn.rollback();   // 未提交修改被撤销
            conn.commit();     // 显式提交
            """;
}
