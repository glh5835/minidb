package minidb.web;

import com.fasterxml.jackson.databind.JsonNode;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.exec.Executor;
import minidb.sql.Ast;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Page;
import minidb.storage.Schema;
import minidb.storage.Table;
import minidb.txn.TxnSession;
import minidb.web.session.DatabaseRegistry;
import minidb.web.session.SessionManager;
import minidb.web.session.WebSession;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio 服务层：所有操作都落在真实 MiniDB 内核上（Executor/Database/Table），
 * 不实现任何第二套数据库逻辑。Controller 只做参数校验与路由。
 */
public final class MiniDbService {
    /** SQL 结果最大返回行数（计划书 §11，内核执行入口限制，超限置 RESULT_TRUNCATED）。 */
    public static final int MAX_RESULT_ROWS = 1000;
    /** 浏览器分页默认与上限。 */
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 500;

    public final DatabaseRegistry registry;
    public final SessionManager sessions;
    public final boolean devMode;
    /** 相对路径的基准目录（--data-dir）。 */
    public final java.nio.file.Path dataDir;

    public MiniDbService(DatabaseRegistry registry, SessionManager sessions, boolean devMode) {
        this(registry, sessions, devMode, java.nio.file.Path.of("").toAbsolutePath());
    }

    public MiniDbService(DatabaseRegistry registry, SessionManager sessions, boolean devMode,
                         java.nio.file.Path dataDir) {
        this.registry = registry;
        this.sessions = sessions;
        this.devMode = devMode;
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    private static Api.ApiException needDb() {
        return new Api.ApiException("DATABASE_NOT_OPEN", "尚未打开数据库，请先在开始页创建或打开数据库。", 409);
    }

    private Database db(WebSession s) {
        if (!s.hasDb()) throw needDb();
        return s.dbEntry.db;
    }

    private Executor exec(WebSession s) {
        if (s.executor == null) throw needDb();
        return s.executor;
    }

    private <T> T inKernel(WebSession s, KernelCall<T> call) {
        try {
            return call.run();
        } catch (MiniDbException e) {
            throw Api.map(e);
        }
    }

    private interface KernelCall<T> {
        T run();
    }

    // ---------------- 会话 ----------------

    public Map<String, Object> sessionState(WebSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.id);
        data.put("dbPath", s.dbPath);
        data.put("hasDb", s.hasDb());
        data.put("txn", txnState(s));
        if (s.hasDb()) {
            data.put("tableNames", inKernel(s, () -> db(s).tableNames()));
            data.put("isWorkDb", isWorkDb(s.dbPath));
        }
        return data;
    }

    /** 工作库/实验库标注：实验库统一放 .minidb-lab 目录或以 .exp.db 结尾。 */
    public static boolean isWorkDb(String path) {
        if (path == null) return true;
        String name = path.replace('\\', '/');
        return !name.contains(".minidb-lab") && !name.endsWith(".exp.db");
    }

    // ---------------- 数据库 ----------------

    public Map<String, Object> createDatabase(WebSession s, String path) {
        if (path == null || path.isBlank())
            throw new Api.ApiException("VALIDATION_ERROR", "缺少数据库文件路径 path");
        validateDbPath(path);
        String resolved = resolvePath(path);
        if (DatabaseRegistry.exists(resolved))
            throw new Api.ApiException("DATABASE_ALREADY_EXISTS", "数据库文件已存在: " + path);
        DatabaseRegistry.Entry e = inKernel(s, () -> registry.acquire(resolved, true));
        // acquire 已 +1；绑定再次 +1 的话会泄漏。改为 acquire 只给调用方一次引用。
        try {
            bind(s, e, resolved);
        } catch (RuntimeException ex) {
            registry.release(e);
            throw ex;
        }
        Map<String, Object> data = sessionState(s);
        return data;
    }

    public Map<String, Object> openDatabase(WebSession s, String path) {
        if (path == null || path.isBlank())
            throw new Api.ApiException("VALIDATION_ERROR", "缺少数据库文件路径 path");
        validateDbPath(path);
        String resolved = resolvePath(path);
        if (!DatabaseRegistry.exists(resolved))
            throw new Api.ApiException("DATABASE_NOT_FOUND", "数据库文件不存在: " + path);
        DatabaseRegistry.Entry e = inKernel(s, () -> registry.acquire(resolved, false));
        try {
            bind(s, e, resolved);
        } catch (RuntimeException ex) {
            registry.release(e);
            throw ex;
        }
        return sessionState(s);
    }

    /** 相对路径基于 --data-dir 解析（启动目录语义对用户更一致）。 */
    private String resolvePath(String path) {
        java.nio.file.Path p = java.nio.file.Path.of(path);
        if (p.isAbsolute()) return p.normalize().toString();
        return dataDir.resolve(p).normalize().toString();
    }

    private void validateDbPath(String path) {
        // 允许相对路径（相对工作目录），但禁止目录穿越到盘符根以外没有实际危害的场景不拦；
        // 仅拦截明显非法的路径字符（Windows 保留字符）
        if (path.chars().anyMatch(c -> "<>|?*\"".indexOf(c) >= 0))
            throw new Api.ApiException("VALIDATION_ERROR", "路径包含非法字符: " + path);
    }

    private void bind(WebSession s, DatabaseRegistry.Entry e, String path) {
        DatabaseRegistry.Entry old = s.dbEntry;
        s.dbPath = e.path.toString();
        s.dbEntry = e;
        s.executor = new Executor(e.db);
        s.txn = null;
        if (old != null && old != e) registry.release(old);
    }

    public Map<String, Object> closeDatabase(WebSession s) {
        if (s.hasDb()) {
            s.rollbackIfActive();
            DatabaseRegistry.Entry e = s.dbEntry;
            s.dbPath = null;
            s.dbEntry = null;
            s.executor = null;
            registry.release(e);
        }
        return sessionState(s);
    }

    /** 数据库文件存在性检查（最近列表展示用）。 */
    public Map<String, Object> checkDatabase(Map<String, Object> body) {
        String path = Json.str(body, "path", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        data.put("exists", DatabaseRegistry.exists(path));
        data.put("open", !path.isEmpty() && registry.isOpen(path));
        return data;
    }

    /** 示例数据库（school.db）：学生/课程/成绩三表 + 数据 + 索引，全部经真实内核。 */
    public Map<String, Object> createSampleDatabase(WebSession s, String dir) {
        java.nio.file.Path base = dir == null || dir.isBlank()
                ? java.nio.file.Path.of("").toAbsolutePath()
                : java.nio.file.Path.of(dir).toAbsolutePath();
        java.nio.file.Path file = base.resolve("school.db");
        if (Files.exists(file))
            throw new Api.ApiException("DATABASE_ALREADY_EXISTS", "示例数据库已存在: " + file);
        String path = file.toString();
        DatabaseRegistry.Entry e = inKernel(s, () -> registry.acquire(path, true));
        try {
            bind(s, e, path);
            Database db = e.db;
            inKernel(s, () -> {
                db.createTable("student", List.of(
                        new Column("id", ColumnType.INT, 0),
                        new Column("name", ColumnType.VARCHAR, 50),
                        new Column("age", ColumnType.INT, 0),
                        new Column("score", ColumnType.DOUBLE, 0)));
                db.createTable("course", List.of(
                        new Column("id", ColumnType.INT, 0),
                        new Column("title", ColumnType.VARCHAR, 100),
                        new Column("credit", ColumnType.INT, 0)));
                db.createTable("score", List.of(
                        new Column("student_id", ColumnType.INT, 0),
                        new Column("course_id", ColumnType.INT, 0),
                        new Column("grade", ColumnType.DOUBLE, 0)));
                return null;
            });
            // 数据经 Executor（真实 SQL 路径）
            Executor ex = s.executor;
            inKernel(s, () -> ex.execute("""
                    INSERT INTO student VALUES
                    (1, '张伟', 20, 88.5), (2, '王芳', 21, 92.0), (3, '李娜', 20, 76.5),
                    (4, '刘洋', 22, 65.0), (5, '陈静', 21, 84.0), (6, '杨帆', 20, 58.5)"""));
            inKernel(s, () -> ex.execute("""
                    INSERT INTO course VALUES
                    (101, '数据库系统', 4), (102, '操作系统', 4), (103, '计算机网络', 3)"""));
            inKernel(s, () -> ex.execute("""
                    INSERT INTO score VALUES
                    (1, 101, 88.5), (2, 101, 92.0), (3, 102, 76.5),
                    (4, 102, 65.0), (5, 103, 84.0), (6, 103, 58.5)"""));
            inKernel(s, () -> ex.execute("CREATE INDEX idx_student_id ON student(id)"));
            inKernel(s, () -> ex.execute("CREATE INDEX idx_student_name ON student(name)"));
            inKernel(s, () -> ex.execute("CREATE INDEX idx_score_student ON score(student_id)"));
        } catch (RuntimeException ex2) {
            // 建库失败：关闭并删除半成品文件，允许重试
            try {
                registry.release(e);
            } catch (RuntimeException ignored) {
            }
            try {
                Files.deleteIfExists(java.nio.file.Path.of(path));
                Files.deleteIfExists(java.nio.file.Path.of(path + ".wal"));
            } catch (Exception ignored) {
            }
            throw ex2;
        }
        return sessionState(s);
    }

    // ---------------- 表 ----------------

    public List<Map<String, Object>> tables(WebSession s) {
        Database d = db(s);
        List<String> names = inKernel(s, d::tableNames);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            Table t = d.getTable(name);
            m.put("rowCount", t.rowCount());
            m.put("pageCount", t.pageCount());
            m.put("fixedLength", t.schema().fixedLength());
            List<Map<String, Object>> idx = new ArrayList<>();
            for (Database.IndexEntry ie : d.indexesFor(name)) {
                idx.add(Map.of("name", ie.meta().name(), "column", ie.meta().column(),
                        "keyType", ie.meta().keyType()));
            }
            m.put("indexes", idx);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> schema(WebSession s, String table) {
        Database d = db(s);
        Schema schema = inKernel(s, () -> d.getTable(table)).schema();
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Column c : schema.columns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type().name());
            m.put("maxLength", c.maxLength() > 0 ? c.maxLength() : null);
            m.put("fixed", c.isFixed());
            cols.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", schema.tableName());
        data.put("columns", cols);
        data.put("fixedLength", schema.fixedLength());
        data.put("fixedRecordSize", schema.fixedRecordSize());
        return data;
    }

    /** 建表（表单 → CREATE TABLE SQL → 真实解析执行，保证与 SQL 工作台同一条路径）。 */
    public Map<String, Object> createTable(WebSession s, Map<String, Object> body) {
        String name = Json.str(body, "name", "").trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new Api.ApiException("VALIDATION_ERROR", "表名需以字母或下划线开头，只含字母/数字/下划线: " + name);
        Object colsObj = body.get("columns");
        if (!(colsObj instanceof List<?> cols) || cols.isEmpty())
            throw new Api.ApiException("VALIDATION_ERROR", "至少需要一个字段");
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(name).append(" (");
        boolean first = true;
        for (Object o : cols) {
            if (!(o instanceof Map<?, ?> cm))
                throw new Api.ApiException("VALIDATION_ERROR", "字段定义格式错误");
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) cm;
            String cname = Json.str(c, "name", "").trim();
            String ctype = Json.str(c, "type", "").trim().toUpperCase();
            int size = Json.intVal(c.get("size"), 0);
            if (!cname.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new Api.ApiException("VALIDATION_ERROR", "字段名非法: " + cname);
            ColumnType t = switch (ctype) {
                case "INT", "INTEGER" -> ColumnType.INT;
                case "BIGINT", "LONG" -> ColumnType.BIGINT;
                case "DOUBLE", "FLOAT" -> ColumnType.DOUBLE;
                case "VARCHAR", "STRING" -> ColumnType.VARCHAR;
                default -> throw new Api.ApiException("VALIDATION_ERROR",
                        "不支持的类型 " + ctype + "（内核支持 INT/BIGINT/DOUBLE/VARCHAR）");
            };
            if (!first) sql.append(", ");
            first = false;
            sql.append(cname).append(' ').append(t.name());
            if (t == ColumnType.VARCHAR) {
                if (size <= 0 || size > 3000)
                    throw new Api.ApiException("VALIDATION_ERROR",
                        "VARCHAR 长度需在 1..3000（内核上限）: " + size);
                sql.append('(').append(size).append(')');
            }
        }
        sql.append(')');
        return executeSql(s, sql.toString(), null, MAX_RESULT_ROWS, false);
    }

    public Map<String, Object> dropTable(WebSession s, String table) {
        return executeSql(s, "DROP TABLE " + table, null, MAX_RESULT_ROWS, false);
    }

    // ---------------- 数据浏览（RID 感知，内核分页） ----------------

    public Map<String, Object> rows(WebSession s, String table, Map<String, String> q) {
        Database d = db(s);
        int offset = parseIntOr(q.get("offset"), 0);
        int limit = parseIntOr(q.get("limit"), DEFAULT_PAGE_SIZE);
        int pageSize = (limit <= 0 || limit > MAX_PAGE_SIZE) ? MAX_PAGE_SIZE : limit;
        String sort = q.get("sort");
        boolean desc = "desc".equalsIgnoreCase(q.get("dir"));
        Ast.Expr where = buildFilter(table, q.get("filterColumn"), q.get("filterOp"), q.get("filterValue"), d);
        Table t = d.getTable(table);
        Schema schema = t.schema();
        long t0 = System.nanoTime();
        Executor.BrowsePage page = inKernel(s, () ->
                s.executor.browse(table, where, sort, desc, offset, pageSize));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        List<Map<String, Object>> data = new ArrayList<>(page.rows().size());
        for (int i = 0; i < page.rows().size(); i++)
            data.add(Json.rowToMap(schema, page.rows().get(i), Json.ridToString(page.rids().get(i))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", data);
        out.put("total", t.rowCount());
        out.put("offset", offset);
        out.put("limit", limit);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("elapsedMs", elapsed);
        out.put("meta", meta);
        return out;
    }

    private int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 简单筛选 → AST 谓词（列类型感知的取值转换）。 */
    private Ast.Expr buildFilter(String table, String col, String op, String value, Database d) {
        if (col == null || col.isBlank() || op == null || op.isBlank())
            return null;
        if (value == null) value = "";
        Schema schema = d.getTable(table).schema();
        Column column = schema.column(col);
        String u = op.toUpperCase();
        if (!List.of("=", "!=", "<>", "<", "<=", ">", ">=", "LIKE").contains(u))
            throw new Api.ApiException("VALIDATION_ERROR", "不支持的筛选运算符: " + op);
        String normalized = u;
        if (normalized.equals("LIKE")) {
            return new Ast.LikeOp(new Ast.ColRef(null, column.name()), value, false);
        }
        Object typed = switch (column.type()) {
            case INT -> (Object) Integer.parseInt(value.trim());
            case BIGINT -> (Object) Long.parseLong(value.trim());
            case DOUBLE -> (Object) Double.parseDouble(value.trim());
            case VARCHAR -> (Object) value;
        };
        if (normalized.equals("!=") || normalized.equals("<>"))
            return new Ast.UnaryOp("NOT", new Ast.BinOp("=", new Ast.ColRef(null, column.name()),
                    new Ast.Literal(typed)));
        return new Ast.BinOp(normalized, new Ast.ColRef(null, column.name()), new Ast.Literal(typed));
    }

    public Map<String, Object> insertRow(WebSession s, String table, JsonNode body) {
        Table t = db(s).getTable(table);
        Object[] values = Json.rowValues(body, t.schema().columns());
        long t0 = System.nanoTime();
        Rid rid = inKernel(s, () -> s.executor.insertRow(table, values, s.txn));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rid", Json.ridToString(rid));
        data.put("txnActive", s.txnActive());
        data.put("elapsedMs", elapsed);
        return data;
    }

    public Map<String, Object> updateRow(WebSession s, String table, int pageId, int slot, JsonNode body) {
        Table t = db(s).getTable(table);
        Object[] values = Json.rowValues(body, t.schema().columns());
        Rid rid = new Rid(pageId, slot);
        long t0 = System.nanoTime();
        Executor.UpdateRowResult r = inKernel(s, () ->
                s.executor.updateRowByRid(table, rid, values, s.txn));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldRid", Json.ridToString(r.oldRid()));
        data.put("newRid", Json.ridToString(r.newRid()));
        data.put("migrated", !r.oldRid().equals(r.newRid()));
        data.put("txnActive", s.txnActive());
        data.put("elapsedMs", elapsed);
        return data;
    }

    public Map<String, Object> deleteRow(WebSession s, String table, int pageId, int slot) {
        long t0 = System.nanoTime();
        inKernel(s, () -> {
            s.executor.deleteRowByRid(table, new Rid(pageId, slot), s.txn);
            return null;
        });
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("txnActive", s.txnActive());
        data.put("elapsedMs", elapsed);
        return data;
    }

    // ---------------- SQL 执行 ----------------

    public Map<String, Object> executeSql(WebSession s, String sql) {
        return executeSql(s, sql, null, MAX_RESULT_ROWS, true);
    }

    /** 单条 SQL / 脚本执行入口（真实 Parser + Executor）。脚本用真词法拆分（计划书 §10）。 */
    public Map<String, Object> executeSql(WebSession s, String sql, Integer onlyIndex, int maxRows,
                                          boolean scriptAllowed) {
        if (!s.hasDb()) throw needDb();
        if (sql == null || sql.isBlank())
            throw new Api.ApiException("VALIDATION_ERROR", "SQL 为空");
        long t0 = System.nanoTime();
        List<Map<String, Object>> statements = new ArrayList<>();
        List<String> parts = scriptAllowed ? splitScript(sql) : List.of(sql.trim());
        for (int i = 0; i < parts.size(); i++) {
            if (onlyIndex != null && onlyIndex != i) continue;
            String one = parts.get(i);
            if (one.isBlank()) continue;
            Map<String, Object> result = executeOne(s, one, i, maxRows);
            statements.add(result);
            if (!Boolean.TRUE.equals(result.get("success"))) break; // 脚本遇错停止
        }
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statements", statements);
        data.put("txn", txnState(s));
        data.put("elapsedMs", elapsed);
        return data;
    }

    /** 执行单条语句（含事务语句拦截：SQL 的 BEGIN/COMMIT/ROLLBACK 与按钮共用会话事务）。 */
    private Map<String, Object> executeOne(WebSession s, String sql, int index, int maxRows) {
        int[] pos = lineColOf(sql, 0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("sql", sql);
        m.put("line", pos[0]);
        m.put("column", pos[1]);
        long t0 = System.nanoTime();
        try {
            Object stmt = minidb.sql.Parser.parse(stripSemi(sql));
            // 事务语句 → 会话事务（禁止两套状态机）
            if (stmt instanceof minidb.sql.Token.Type t) {
                switch (t) {
                    case BEGIN -> {
                        if (s.txnActive())
                            throw new MiniDbException(MiniDbException.Code.TXN, "事务已在进行中");
                        s.txn = exec(s).begin(s.defaultIsolation);
                        m.put("message", "BEGIN");
                    }
                    case COMMIT -> {
                        TxnSession tx = requireTxn(s);
                        s.txn = null;
                        exec(s).commit(tx);
                        m.put("message", "COMMIT");
                    }
                    case ROLLBACK -> {
                        TxnSession tx = requireTxn(s);
                        s.txn = null;
                        exec(s).rollback(tx);
                        m.put("message", "ROLLBACK");
                    }
                    default -> throw new MiniDbException(MiniDbException.Code.EXEC, "不支持的语句");
                }
            } else if (stmt instanceof Ast.SelectStmt sel) {
                // SELECT：内核侧截断保护（maxRows+1 探测截断）
                int cap = Math.min(maxRows, MAX_RESULT_ROWS);
                Executor.Result r = exec(s).runSelect(sel, s.txn, cap + 1);
                boolean truncated = r.rows().size() > cap;
                List<Object[]> shown = truncated ? r.rows().subList(0, cap) : r.rows();
                m.put("columns", r.columns());
                m.put("rows", rowsToJson(r.columns(), shown));
                m.put("rowCount", shown.size());
                m.put("truncated", truncated);
                if (truncated)
                    m.put("notice", "结果超过 " + cap + " 行，已截断显示。请增加 WHERE 条件缩小查询范围。");
            } else {
                // DDL/DML：显式事务期间拒绝（计划书 §14：DDL 不支持事务回滚）
                boolean ddl = stmt instanceof Ast.CreateTableStmt || stmt instanceof Ast.DropTableStmt
                        || stmt instanceof Ast.CreateIndexStmt || stmt instanceof Ast.DropIndexStmt;
                if (ddl && s.txnActive())
                    throw new Api.ApiException("TRANSACTION_ACTIVE",
                            "当前 MiniDB 的结构变更暂不支持完整事务回滚。请提交或回滚当前事务后再执行 DDL。", 409);
                Executor.Result r = exec(s).execute(stmt, s.txn);
                m.put("message", r.message());
                if (!r.columns().isEmpty()) {
                    m.put("columns", r.columns());
                    m.put("rows", rowsToJson(r.columns(), r.rows()));
                    m.put("rowCount", r.rows().size());
                } else {
                    m.put("rowCount", affectedOf(r.message()));
                }
            }
            m.put("success", true);
        } catch (MiniDbException e) {
            Api.ApiException ae = Api.map(e);
            int[] lc = parsePosOf(e, sql);
            m.put("success", false);
            m.put("error", Map.of("code", ae.code, "message", ae.getMessage(),
                    "line", lc[0], "column", lc[1]));
        } catch (Api.ApiException e) {
            m.put("success", false);
            m.put("error", Map.of("code", e.code, "message", e.getMessage(), "line", pos[0], "column", pos[1]));
        } catch (RuntimeException e) {
            m.put("success", false);
            m.put("error", Map.of("code", "SQL_EXECUTION_ERROR",
                    "message", String.valueOf(e.getMessage()), "line", pos[0], "column", pos[1]));
        }
        m.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        m.put("txn", txnState(s));
        return m;
    }

    private TxnSession requireTxn(WebSession s) {
        TxnSession tx = s.txn;
        if (tx == null || tx.finished)
            throw new MiniDbException(MiniDbException.Code.TXN, "没有活动事务");
        return tx;
    }

    private static int affectedOf(String message) {
        if (message == null) return 0;
        var matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 行值 → JSON：BIGINT 字符串化。 */
    private List<Object> rowsToJson(List<String> columns, List<Object[]> rows) {
        List<Object> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Object> j = new ArrayList<>(row.length);
            for (Object v : row) j.add(Json.toJsonValue(v));
            out.add(j);
        }
        return out;
    }

    /**
     * 真词法脚本拆分：按 Lexer 的 SEMI token 切分（正确处理引号内的 ;）。
     * 返回的每条语句保留原始文本（不含结尾分号）。
     */
    public static List<String> splitScript(String script) {
        List<String> out = new ArrayList<>();
        List<minidb.sql.Token> tokens;
        try {
            tokens = new minidb.sql.Lexer(script).tokenize();
        } catch (MiniDbException e) {
            // 词法整体失败：按原样返回单条，让执行报错给用户
            return List.of(script.trim());
        }
        int start = 0;
        for (minidb.sql.Token t : tokens) {
            if (t.type == minidb.sql.Token.Type.SEMI) {
                String part = script.substring(start, t.pos).trim();
                if (!part.isEmpty()) out.add(part);
                start = t.pos + 1;
            }
        }
        if (start < script.length()) {
            String tail = script.substring(start).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        if (out.isEmpty() && !script.isBlank()) out.add(script.trim());
        return out;
    }

    /** 语句在脚本中的 1-based 行列。 */
    private static int[] lineColOf(String script, int offset) {
        int line = 1, col = 1;
        for (int i = 0; i < offset && i < script.length(); i++) {
            if (script.charAt(i) == '\n') {
                line++;
                col = 1;
            } else col++;
        }
        return new int[]{line, col};
    }

    /** 从 PARSE 异常消息 "(位置 pos)" 解出行列。 */
    private static int[] parsePosOf(MiniDbException e, String sql) {
        String msg = String.valueOf(e.getMessage());
        var m = java.util.regex.Pattern.compile("位置 (\\d+)").matcher(msg);
        if (m.find()) {
            int pos = Integer.parseInt(m.group(1));
            int[] lc = lineColOf(sql, Math.min(pos, Math.max(0, sql.length() - 1)));
            return new int[]{lc[0], lc[1]};
        }
        return new int[]{1, 1};
    }

    private static String stripSemi(String sql) {
        String s = sql.trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    // ---------------- 事务 ----------------

    public Map<String, Object> txnState(WebSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        TxnSession tx = s.txn;
        boolean active = tx != null && !tx.finished;
        data.put("active", active);
        data.put("autoCommit", !active);
        data.put("isolation", active ? tx.isolation.name() : s.defaultIsolation.name());
        if (active) {
            data.put("txnId", tx.txnId);
            data.put("heldLocks", new ArrayList<>(tx.heldLocks));
        }
        return data;
    }

    public Map<String, Object> begin(WebSession s, Map<String, Object> body) {
        if (!s.hasDb()) throw needDb();
        if (s.txnActive())
            throw new Api.ApiException("TRANSACTION_ACTIVE", "事务已在进行中", 409);
        String iso = Json.str(body, "isolation", s.defaultIsolation.name());
        TxnSession.Isolation isolation = switch (iso.toUpperCase()) {
            case "READ_COMMITTED" -> TxnSession.Isolation.READ_COMMITTED;
            case "REPEATABLE_READ" -> TxnSession.Isolation.REPEATABLE_READ;
            default -> throw new Api.ApiException("VALIDATION_ERROR", "未知隔离级别: " + iso);
        };
        s.defaultIsolation = isolation;
        long t0 = System.nanoTime();
        s.txn = inKernel(s, () -> exec(s).begin(isolation));
        Map<String, Object> data = txnState(s);
        data.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        return data;
    }

    public Map<String, Object> commit(WebSession s) {
        if (!s.hasDb()) throw needDb();
        TxnSession tx = s.txn;
        if (tx == null || tx.finished)
            throw new Api.ApiException("TRANSACTION_REQUIRED", "没有活动事务", 409);
        long t0 = System.nanoTime();
        s.txn = null;
        inKernel(s, () -> {
            exec(s).commit(tx);
            return null;
        });
        Map<String, Object> data = txnState(s);
        data.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        return data;
    }

    public Map<String, Object> rollback(WebSession s) {
        if (!s.hasDb()) throw needDb();
        TxnSession tx = s.txn;
        if (tx == null || tx.finished)
            throw new Api.ApiException("TRANSACTION_REQUIRED", "没有活动事务", 409);
        long t0 = System.nanoTime();
        s.txn = null;
        inKernel(s, () -> {
            exec(s).rollback(tx);
            return null;
        });
        Map<String, Object> data = txnState(s);
        data.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        return data;
    }

    // ---------------- 索引 ----------------

    public List<Map<String, Object>> indexes(WebSession s, String table) {
        Database d = db(s);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Database.IndexEntry ie : inKernel(s, () -> table == null || table.isBlank()
                ? allIndexes(d) : d.indexesFor(table))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ie.meta().name());
            m.put("table", ie.tableName());
            m.put("column", ie.meta().column());
            m.put("keyType", ie.meta().keyType());
            m.put("rootPage", ie.meta().rootPage());
            var stats = inKernel(s, () -> ie.tree().stats());
            m.put("height", stats.height());
            m.put("leafNodes", stats.leafNodes());
            m.put("internalNodes", stats.internalNodes());
            m.put("avgLeafUtilization", stats.avgLeafUtilization());
            m.put("totalKeys", stats.totalKeys());
            out.add(m);
        }
        return out;
    }

    private List<Database.IndexEntry> allIndexes(Database d) {
        List<Database.IndexEntry> out = new ArrayList<>();
        for (String name : d.tableNames()) out.addAll(d.indexesFor(name));
        return out;
    }

    public Map<String, Object> createIndex(WebSession s, Map<String, Object> body) {
        String name = Json.str(body, "name", "").trim();
        String table = Json.str(body, "table", "").trim();
        String column = Json.str(body, "column", "").trim();
        if (name.isEmpty() || table.isEmpty() || column.isEmpty())
            throw new Api.ApiException("VALIDATION_ERROR", "需要 name / table / column");
        return executeSql(s, "CREATE INDEX " + name + " ON " + table + "(" + column + ")",
                null, MAX_RESULT_ROWS, false);
    }

    public Map<String, Object> dropIndex(WebSession s, String name) {
        return executeSql(s, "DROP INDEX " + name, null, MAX_RESULT_ROWS, false);
    }

    /** B+ 树结构快照（真实内核，前端只做 Snapshot → SVG）。 */
    public Map<String, Object> btreeSnapshot(WebSession s, String indexName) {
        Database d = db(s);
        long t0 = System.nanoTime();
        var snap = inKernel(s, () -> d.getIndex(indexName).snapshot(keyTypeOf(d, indexName)));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        List<Map<String, Object>> nodes = new ArrayList<>(snap.nodes().size());
        for (var n : snap.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageId", n.pageId());
            m.put("kind", n.kind());
            m.put("keys", n.keys());
            m.put("children", n.children());
            m.put("next", n.next());
            m.put("prev", n.prev());
            m.put("rids", n.rids());
            m.put("usedBytes", n.usedBytes());
            m.put("totalBytes", n.totalBytes());
            nodes.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("root", snap.rootPage());
        data.put("height", snap.height());
        data.put("stringKeys", snap.keyType() == 1);
        data.put("nodes", nodes);
        data.put("elapsedMs", elapsed);
        return data;
    }

    private String keyTypeOf(Database d, String indexName) {
        for (String t : d.tableNames())
            for (Database.IndexEntry ie : d.indexesFor(t))
                if (ie.meta().name().equalsIgnoreCase(indexName)) return ie.meta().keyType();
        throw new MiniDbException(MiniDbException.Code.CATALOG, "索引不存在: " + indexName);
    }

    // ---------------- 快照 ----------------

    public Map<String, Object> bufferPoolSnapshot(WebSession s) {
        Database d = db(s);
        var pool = d.engine().pool();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capacity", pool.capacity());
        data.put("size", pool.size());
        data.put("hits", pool.hits());
        data.put("misses", pool.misses());
        data.put("evictions", pool.evictions());
        data.put("writebacks", pool.writebacks());
        data.put("diskPages", d.engine().pageCount());
        data.put("bitmapPages", d.engine().bitmapCount());
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
        return data;
    }

    public Map<String, Object> flush(WebSession s) {
        long t0 = System.nanoTime();
        inKernel(s, () -> {
            db(s).flush();
            return null;
        });
        return Map.of("flushed", true, "elapsedMs", (System.nanoTime() - t0) / 1_000_000);
    }

    public Map<String, Object> lockSnapshot(WebSession s) {
        Database d = db(s);
        List<Map<String, Object>> locks = new ArrayList<>();
        for (var l : d.lockManager().snapshot()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.key());
            m.put("holders", l.holders());
            m.put("mode", l.mode());
            m.put("waiting", l.waiting());
            locks.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("locks", locks);
        return data;
    }

    /** 表页视图（存储实验）：页链 + 每页头信息（经 BufferPool 读真实页）。 */
    public Map<String, Object> pageSnapshot(WebSession s, String table) {
        Database d = db(s);
        Table t = d.getTable(table);
        // 表页链：从表对象无法直接拿页列表，用 scan 的 RID 收集 + 页头读取
        java.util.TreeSet<Integer> pageIds = new java.util.TreeSet<>();
        var it = t.scan();
        while (it.hasNext()) {
            var row = it.next();
            pageIds.add(row.rid().pageId());
        }
        List<Map<String, Object>> pages = new ArrayList<>();
        for (int pid : pageIds) {
            var header = d.engine().pool().withPage(pid, false, p -> {
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
                m.put("usedBytes", Page.SIZE - minidb.storage.TablePageHeader.totalFree(b));
                return m;
            });
            pages.add(header);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", table);
        data.put("pageSize", minidb.storage.Page.SIZE);
        data.put("rowCount", t.rowCount());
        data.put("pageCount", t.pageCount());
        data.put("totalFreeBytes", t.totalFreeBytes());
        data.put("fixedLength", t.schema().fixedLength());
        data.put("pages", pages);
        return data;
    }

    // ---------------- SQL 执行流程（Lexer → Parser → Plan → Result） ----------------

    public Map<String, Object> sqlPipeline(WebSession s, String sql) {
        if (!s.hasDb()) throw needDb();
        Map<String, Object> data = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        // 1. Tokens
        List<Map<String, Object>> tokens = new ArrayList<>();
        try {
            for (var tk : new minidb.sql.Lexer(stripSemi(sql)).tokenize()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", tk.type.name());
                m.put("text", tk.text);
                m.put("pos", tk.pos);
                tokens.add(m);
            }
        } catch (MiniDbException e) {
            throw Api.map(e);
        }
        data.put("tokens", tokens);
        // 2. AST
        Object stmt;
        try {
            stmt = minidb.sql.Parser.parse(stripSemi(sql));
        } catch (MiniDbException e) {
            throw Api.map(e);
        }
        data.put("ast", stmt instanceof Ast.SelectStmt sel ? AstJson.select(sel) : AstJson.any(stmt));
        // 3. 计划（仅 SELECT）
        if (stmt instanceof Ast.SelectStmt sel) {
            data.put("planText", inKernel(s, () -> exec(s).explain(stripSemi(sql))));
            // 4. 真实结果（内核截断保护）
            Executor.Result r = inKernel(s, () -> exec(s).runSelect(sel, s.txn, MAX_RESULT_ROWS + 1));
            boolean truncated = r.rows().size() > MAX_RESULT_ROWS;
            data.put("columns", r.columns());
            data.put("rows", rowsToJson(r.columns(),
                    truncated ? r.rows().subList(0, MAX_RESULT_ROWS) : r.rows()));
            data.put("rowCount", truncated ? MAX_RESULT_ROWS : r.rows().size());
            data.put("truncated", truncated);
        } else {
            data.put("planText", "（仅 SELECT 提供计划树）");
        }
        data.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
        return data;
    }

    // ---------------- 执行计划 ----------------

    public Map<String, Object> plan(WebSession s, String sql) {
        if (!s.hasDb()) throw needDb();
        String text = inKernel(s, () -> exec(s).explain(stripSemi(sql)));
        List<Map<String, Object>> tree = parsePlanText(text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("tree", tree);
        return data;
    }

    /** explain 缩进文本 → 树（2 空格一级）。 */
    static List<Map<String, Object>> parsePlanText(String text) {
        List<Map<String, Object>> roots = new ArrayList<>();
        List<Map<String, Object>> stack = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            int depth = (line.length() - line.stripLeading().length()) / 2;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("op", line.strip());
            node.put("children", new ArrayList<Map<String, Object>>());
            while (depths.size() > depth) {
                depths.remove(depths.size() - 1);
                stack.remove(stack.size() - 1);
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> kids =
                        (List<Map<String, Object>>) stack.get(stack.size() - 1).get("children");
                kids.add(node);
            }
            stack.add(node);
            depths.add(depth);
        }
        return roots;
    }

    // ---------------- WAL ----------------

    /** WAL 记录解码视图（真实 readAll）。 */
    public Map<String, Object> wal(WebSession s) {
        Database d = db(s);
        List<Map<String, Object>> recs = new ArrayList<>();
        for (var r : inKernel(s, () -> d.wal().readAll())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lsn", String.valueOf(r.lsn()));
            m.put("type", walTypeName(r.type()));
            m.put("txnId", String.valueOf(r.txnId()));
            m.put("table", r.table());
            m.put("rid", r.pageId() >= 0 ? r.pageId() + "/" + r.slot() : null);
            m.put("hasBefore", r.before() != null);
            m.put("hasAfter", r.after() != null);
            recs.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", recs);
        data.put("fileBytes", d.wal().size());
        return data;
    }

    private static String walTypeName(byte type) {
        return switch (type) {
            case minidb.wal.WalLog.BEGIN -> "BEGIN";
            case minidb.wal.WalLog.INSERT -> "INSERT";
            case minidb.wal.WalLog.DELETE -> "DELETE";
            case minidb.wal.WalLog.UPDATE -> "UPDATE";
            case minidb.wal.WalLog.COMMIT -> "COMMIT";
            case minidb.wal.WalLog.ABORT -> "ABORT";
            default -> "TYPE" + type;
        };
    }
}
