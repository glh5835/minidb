package minidb.web;

import minidb.web.session.DatabaseRegistry;
import minidb.web.session.SessionManager;
import minidb.web.session.WebSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Web Service 层测试（计划书 §46 第二层）+ 接口契约测试（第三层）：
 * BIGINT 字符串、中文、错误码、事务状态、结果截断、RID、JSON 类型。
 * 直接驱动 MiniDbService / LabService（真实内核）；服务方法返回裸 data。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebServiceTest {
    static Path dir;
    static DatabaseRegistry registry;
    static SessionManager sessions;
    static MiniDbService svc;
    static LabService labs;
    static WebSession s;

    @BeforeAll
    static void setup() throws Exception {
        dir = Files.createTempDirectory("minidb-web-test");
        registry = new DatabaseRegistry();
        sessions = new SessionManager(registry);
        svc = new MiniDbService(registry, sessions, false);
        labs = new LabService(registry, dir);
        s = sessions.create();
    }

    @AfterAll
    static void teardown() {
        sessions.close();
        registry.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object o) {
        return (List<T>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Object list) {
        return ((List<Map<String, Object>>) list).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static String state(Map<String, Object> poll, String side) {
        return String.valueOf(((Map<String, Object>) poll.get(side)).get("state"));
    }

    /** 等待某会话的挂起实验操作收敛（done/idle），最多 5s。 */
    private static void assertConverged(String side) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> poll = labs.txnPoll();
            String st = state(poll, side);
            if (st.equals("done") || st.equals("idle") || st.equals("error")) return;
            Thread.sleep(50);
        }
        fail(side + " 的挂起操作未收敛: " + labs.txnPoll());
    }

    private static Map<String, Object> execOne(String sql) {
        List<Map<String, Object>> stmts = cast(svc.executeSql(s, sql).get("statements"));
        return stmts.get(stmts.size() - 1);
    }

    // ---------------- 数据库 ----------------

    @Test
    @Order(1)
    void createOpenCloseDatabase() {
        String path = dir.resolve("main.db").toString();
        Map<String, Object> created = svc.createDatabase(s, path);
        assertEquals(path, created.get("dbPath"));
        assertEquals(Boolean.TRUE, created.get("isWorkDb"));

        var e = assertThrows(Api.ApiException.class, () -> svc.createDatabase(s, path));
        assertEquals("DATABASE_ALREADY_EXISTS", e.code);

        var e2 = assertThrows(Api.ApiException.class,
                () -> svc.openDatabase(s, dir.resolve("missing.db").toString()));
        assertEquals("DATABASE_NOT_FOUND", e2.code);

        svc.closeDatabase(s);
        assertFalse(s.hasDb());
        svc.openDatabase(s, path);
        assertTrue(s.hasDb());
    }

    @Test
    @Order(2)
    void sessionRequiresDb() {
        WebSession fresh = sessions.create();
        var e = assertThrows(Api.ApiException.class, () -> svc.tables(fresh));
        assertEquals("DATABASE_NOT_OPEN", e.code);
        sessions.close(fresh.id);
    }

    // ---------------- 表与 CRUD（RID 契约） ----------------

    @Test
    @Order(10)
    void createTableAndBrowseContract() {
        execOne("CREATE TABLE student (id INT, name VARCHAR(50), big BIGINT, score DOUBLE)");

        var schema = svc.schema(s, "student");
        assertEquals(4, ((List<?>) schema.get("columns")).size());

        // RID 精确插入：BIGINT 用字符串传（JS 精度契约）、中文、DOUBLE
        svc.insertRow(s, "student", Json.MAPPER.valueToTree(Map.of(
                "id", 1, "name", "张伟", "big", "9223372036854775807", "score", 88.5)));
        svc.insertRow(s, "student", Json.MAPPER.valueToTree(Map.of(
                "id", 2, "name", "王芳", "big", "-9223372036854775808", "score", 92.0)));

        // 浏览：_rid 存在，BIGINT 输出为字符串
        Map<String, Object> page = svc.rows(s, "student", Map.of("offset", "0", "limit", "50"));
        assertEquals(2, ((Number) page.get("total")).intValue());
        List<Map<String, Object>> rows = cast(page.get("rows"));
        Map<String, Object> row1 = rows.get(0);
        assertTrue(String.valueOf(row1.get("_rid")).matches("\\d+/\\d+"), "RID 形如 page/slot");
        assertEquals("9223372036854775807", String.valueOf(row1.get("big")), "BIGINT JSON 字符串化");
        assertEquals("张伟", row1.get("name"), "中文往返");

        // 分页 / 排序 / 筛选
        assertEquals(1, cast(svc.rows(s, "student", Map.of("offset", "1", "limit", "1")).get("rows")).size());
        Map<String, Object> p3 = svc.rows(s, "student", Map.of("sort", "id", "dir", "desc", "limit", "2"));
        assertEquals("王芳", first(p3.get("rows")).get("name"));
        Map<String, Object> p4 = svc.rows(s, "student", Map.of(
                "filterColumn", "id", "filterOp", "=", "filterValue", "2"));
        assertEquals(1, cast(p4.get("rows")).size());
    }

    @Test
    @Order(11)
    void updateByRidWithMigrationAndStale() {
        Map<String, Object> page = svc.rows(s, "student", Map.of(
                "filterColumn", "id", "filterOp", "=", "filterValue", "1"));
        Map<String, Object> row = first(page.get("rows"));
        String rid = String.valueOf(row.get("_rid"));
        int p = Integer.parseInt(rid.substring(0, rid.indexOf('/')));
        int slot = Integer.parseInt(rid.substring(rid.indexOf('/') + 1));

        // VARCHAR 变长更新（增长文本）→ 可能迁移，返回新旧 RID
        Map<String, Object> upd = svc.updateRow(s, "student", p, slot, Json.MAPPER.valueToTree(Map.of(
                "id", 1, "name", "张伟-更新后更长的名字", "big", "123456789012345", "score", 66.5)));
        assertEquals(rid, upd.get("oldRid"));
        assertNotNull(upd.get("newRid"));

        // 用旧 RID 再改 → 若迁移过则报 RID_STALE，否则正常
        if (Boolean.TRUE.equals(upd.get("migrated"))) {
            var e = assertThrows(Api.ApiException.class, () -> svc.updateRow(s, "student", p, slot,
                    Json.MAPPER.valueToTree(Map.of("id", 1, "name", "x", "big", "1", "score", 1.0))));
            assertEquals("RID_STALE", e.code);
        } else {
            svc.updateRow(s, "student", p, slot,
                    Json.MAPPER.valueToTree(Map.of("id", 1, "name", "x", "big", "1", "score", 1.0)));
        }

        // 删除 + 同 RID 再删 → RID_STALE
        Map<String, Object> page2 = svc.rows(s, "student", Map.of(
                "filterColumn", "id", "filterOp", "=", "filterValue", "2"));
        Map<String, Object> row2 = first(page2.get("rows"));
        String rid2 = String.valueOf(row2.get("_rid"));
        int p2 = Integer.parseInt(rid2.substring(0, rid2.indexOf('/')));
        int s2 = Integer.parseInt(rid2.substring(rid2.indexOf('/') + 1));
        svc.deleteRow(s, "student", p2, s2);
        var e2 = assertThrows(Api.ApiException.class, () -> svc.deleteRow(s, "student", p2, s2));
        assertEquals("RID_STALE", e2.code);
    }

    // ---------------- SQL 工作台 ----------------

    @Test
    @Order(20)
    void sqlExecuteSelectAndMessage() {
        Map<String, Object> st = execOne("SELECT id, name, score FROM student WHERE id > 0 ORDER BY id");
        assertTrue((Boolean) st.get("success"), "SELECT 失败: " + st.get("error"));
        assertEquals(List.of("student.id", "student.name", "student.score"), st.get("columns"));
        assertTrue(((Number) st.get("rowCount")).intValue() >= 1);
    }

    @Test
    @Order(21)
    void sqlScriptSplitWithSemicolonInString() {
        // 真词法拆分：字符串里的 ; 不切分
        String script = """
                INSERT INTO student VALUES (100, 'hello;world', 7, 1.0);
                SELECT id, name FROM student WHERE id = 100;
                DELETE FROM student WHERE id = 100;""";
        List<Map<String, Object>> stmts = cast(svc.executeSql(s, script).get("statements"));
        assertEquals(3, stmts.size(), "引号内分号不被拆分");
        for (Map<String, Object> st : stmts) assertTrue((Boolean) st.get("success"), String.valueOf(st));
        Map<String, Object> sel = stmts.get(1);
        assertEquals("[[100, hello;world]]", sel.get("rows").toString(), "分号完整保留在字符串值中");
    }

    @Test
    @Order(22)
    void sqlParseErrorHasLineColumnAndCode() {
        Map<String, Object> st = execOne("SELECT FROM WHERE");
        assertFalse((Boolean) st.get("success"));
        Map<String, Object> err = map(st.get("error"));
        assertEquals("SQL_PARSE_ERROR", err.get("code"));
        assertNotNull(err.get("line"));
        assertNotNull(err.get("column"));
    }

    @Test
    @Order(23)
    void resultTruncationAt1000() {
        execOne("CREATE TABLE trunc_t (id INT)");
        StringBuilder sb = new StringBuilder("INSERT INTO trunc_t VALUES ");
        for (int i = 1; i <= 1005; i++) {
            if (i > 1) sb.append(", ");
            sb.append("(").append(i).append(")");
        }
        execOne(sb.toString());
        Map<String, Object> st = execOne("SELECT * FROM trunc_t");
        assertEquals(1000, ((Number) st.get("rowCount")).intValue(), "内核执行入口截断 1000 行");
        assertEquals(Boolean.TRUE, st.get("truncated"));
        assertNotNull(st.get("notice"));
        execOne("DROP TABLE trunc_t");
    }

    @Test
    @Order(24)
    void uniqueIndexViolationCode() {
        execOne("CREATE TABLE uniq_t (id INT, name VARCHAR(20))");
        execOne("INSERT INTO uniq_t VALUES (1, 'a')");
        execOne("CREATE INDEX idx_uniq ON uniq_t(id)");
        Map<String, Object> st = execOne("INSERT INTO uniq_t VALUES (1, 'dup')");
        assertFalse((Boolean) st.get("success"));
        assertEquals("UNIQUE_CONSTRAINT_VIOLATION", map(st.get("error")).get("code"));
        // autoCommit 回滚：重复行未落入
        Map<String, Object> sel = execOne("SELECT COUNT(*) AS c FROM uniq_t");
        assertTrue(sel.get("rows").toString().contains("1"), "重复插入被整体回滚");
        execOne("DROP TABLE uniq_t");
    }

    // ---------------- 事务 ----------------

    @Test
    @Order(30)
    void transactionFlowSharedWithSql() {
        Map<String, Object> st0 = svc.txnState(s);
        assertEquals(Boolean.FALSE, st0.get("active"));

        // SQL 文本 BEGIN 与按钮共用同一会话事务
        execOne("BEGIN");
        assertEquals(Boolean.TRUE, svc.txnState(s).get("active"));
        var e = assertThrows(Api.ApiException.class, () -> svc.begin(s, Map.of()));
        assertEquals("TRANSACTION_ACTIVE", e.code);

        // 事务内插入 → 回滚 → 不可见
        svc.insertRow(s, "student", Json.MAPPER.valueToTree(Map.of(
                "id", 900, "name", "tx-row", "big", "1", "score", 1.0)));
        assertTrue(execOne("SELECT COUNT(*) FROM student WHERE id = 900").get("rows").toString().contains("1"),
                "事务内可见");
        execOne("ROLLBACK");
        assertTrue(execOne("SELECT COUNT(*) FROM student WHERE id = 900").get("rows").toString().contains("0"),
                "回滚后不可见");

        // BEGIN → 修改 → COMMIT 持久
        svc.begin(s, Map.of("isolation", "READ_COMMITTED"));
        assertEquals("READ_COMMITTED", svc.txnState(s).get("isolation"));
        execOne("INSERT INTO student VALUES (901, 'committed-row', 2, 2.0)");
        svc.commit(s);
        assertTrue(execOne("SELECT id FROM student WHERE id = 901").get("rows").toString().contains("901"));
        execOne("DELETE FROM student WHERE id = 901");

        // 没有活动事务时 COMMIT 报错
        var e2 = assertThrows(Api.ApiException.class, () -> svc.commit(s));
        assertEquals("TRANSACTION_REQUIRED", e2.code);
    }

    @Test
    @Order(31)
    void ddlRejectedDuringExplicitTxn() {
        svc.begin(s, Map.of());
        Map<String, Object> st = execOne("CREATE TABLE ddl_tx_t (id INT)");
        assertFalse((Boolean) st.get("success"));
        assertEquals("TRANSACTION_ACTIVE", map(st.get("error")).get("code"));
        svc.rollback(s);
    }

    // ---------------- 索引与计划 ----------------

    @Test
    @Order(40)
    void indexCrudAndBtreeSnapshot() {
        execOne("CREATE INDEX idx_web_student_id ON student(id)");
        List<Map<String, Object>> list = svc.indexes(s, null);
        assertTrue(list.stream().anyMatch(m -> "idx_web_student_id".equals(m.get("name"))));

        Map<String, Object> snap = svc.btreeSnapshot(s, "idx_web_student_id");
        assertNotNull(snap.get("root"));
        assertTrue(((Number) snap.get("height")).intValue() >= 1);
        assertFalse(cast(snap.get("nodes")).isEmpty());

        // 建索引后点查走 IndexScan（真实优化器：行数需足够少占比才选索引）
        StringBuilder seed = new StringBuilder("INSERT INTO student VALUES ");
        for (int i = 101; i <= 120; i++) {
            if (i > 101) seed.append(", ");
            seed.append("(").append(i).append(", 's").append(i).append("', ").append(i).append(", 1.5)");
        }
        execOne(seed.toString());
        Map<String, Object> plan = svc.plan(s, "SELECT * FROM student WHERE id = 1");
        assertTrue(String.valueOf(plan.get("text")).contains("IndexScan"));
        assertEquals(1, cast(plan.get("tree")).size(), "计划树单根");

        // DROP INDEX
        svc.dropIndex(s, "idx_web_student_id");
        var e = assertThrows(Api.ApiException.class, () -> svc.btreeSnapshot(s, "idx_web_student_id"));
        assertEquals("INDEX_NOT_FOUND", e.code);
    }

    // ---------------- 快照契约 ----------------

    @Test
    @Order(50)
    void snapshotsContract() {
        Map<String, Object> bp = svc.bufferPoolSnapshot(s);
        assertTrue(((Number) bp.get("capacity")).intValue() > 0);
        assertNotNull(bp.get("pages"));

        assertNotNull(svc.lockSnapshot(s).get("locks"));

        Map<String, Object> pages = svc.pageSnapshot(s, "student");
        assertTrue(((Number) pages.get("pageCount")).intValue() >= 1);
        assertFalse(cast(pages.get("pages")).isEmpty());
        assertNotNull(first(pages.get("pages")).get("numSlots"));

        assertNotNull(svc.wal(s).get("records"));
    }

    // ---------------- 会话过期 ----------------

    @Test
    @Order(60)
    void sessionExpiryReapsTxn() {
        WebSession exp = sessions.create();
        String dbPath = dir.resolve("main.db").toString();
        svc.openDatabase(exp, dbPath);
        svc.begin(exp, Map.of());
        exp.lastTouch = System.currentTimeMillis() - SessionManager.IDLE_TIMEOUT_MS - 1000;
        sessions.reapIdle();
        var e = assertThrows(Api.ApiException.class, () -> sessions.require(exp.id));
        assertEquals("SESSION_EXPIRED", e.code);
        // 未提交事务已被回滚：重新打开同一路径不应有残留锁
        WebSession fresh = sessions.create();
        svc.openDatabase(fresh, dbPath);
        assertEquals(Boolean.TRUE, svc.txnState(fresh).get("autoCommit"));
        sessions.close(fresh.id);
    }

    // ---------------- SQL 执行流程 ----------------

    @Test
    @Order(70)
    void sqlPipelineContract() {
        Map<String, Object> p = svc.sqlPipeline(s, "SELECT name FROM student WHERE id = 1");
        List<?> tokens = cast(p.get("tokens"));
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.get(0).toString().contains("SELECT"));
        assertEquals("SelectStmt", ((Map<?, ?>) p.get("ast")).get("node"));
        String planText = String.valueOf(p.get("planText"));
        assertTrue(planText.contains("SeqScan") || planText.contains("IndexScan"));
        assertNotNull(p.get("rows"));
    }

    // ---------------- 内核实验室 ----------------

    @Test
    @Order(80)
    void recoveryLabRealReport() {
        Map<String, Object> r = labs.recoveryRun();
        List<?> report = cast(r.get("recoveryReport"));
        assertFalse(report.isEmpty(), "有真实恢复报告");
        assertTrue(report.toString().contains("REDO"), "包含 redo 阶段");
        assertTrue(report.toString().contains("UNDO"), "包含 undo 阶段");
        List<?> checks = cast(r.get("consistencyChecks"));
        for (Object c : checks)
            assertEquals(Boolean.TRUE, ((Map<?, ?>) c).get("pass"), "一致性检查通过: " + c);
    }

    @Test
    @Order(81)
    void txnLabLockWaitAndDeadlock() throws Exception {
        assertTrue(labs.txnSetup().toString().contains("Alice"));

        // ---- 锁等待：A 更新 id=1 持锁 → B 更新 id=1 → 真实等待 ----
        labs.txnExec(Map.of("session", "A", "action", "BEGIN"));
        labs.txnExec(Map.of("session", "A", "action", "SQL",
                "sql", "UPDATE account SET balance = 111 WHERE id = 1"));
        labs.txnExec(Map.of("session", "B", "action", "BEGIN"));
        Map<String, Object> bWait = labs.txnExec(Map.of("session", "B", "action", "SQL",
                "sql", "UPDATE account SET balance = 222 WHERE id = 1"));
        if (Boolean.TRUE.equals(bWait.get("pending"))) {
            Map<String, Object> st = labs.txnState();
            assertTrue(st.get("liveRows").toString().contains("111"),
                    "实时视图能看到 A 的未提交修改");
            assertTrue(st.get("locks").toString().contains("account/"), "锁键形如 account/页/槽");
        }
        labs.txnExec(Map.of("session", "A", "action", "COMMIT"));
        assertConverged("B");
        // B 拿到锁做了未提交修改（222），回滚后应回到 A 提交的 111
        labs.txnExec(Map.of("session", "B", "action", "ROLLBACK"));
        assertTrue(labs.txnState().get("liveRows").toString().contains("111"), "A 的提交生效");
        assertFalse(labs.txnState().get("liveRows").toString().contains("222"), "B 的未提交修改已回滚");

        // ---- 死锁：RR 读各自持有 S 锁，交叉更新形成循环等待 ----
        labs.txnExec(Map.of("session", "A", "action", "BEGIN", "isolation", "REPEATABLE_READ"));
        labs.txnExec(Map.of("session", "A", "action", "SQL", "sql", "SELECT * FROM account WHERE id = 1"));
        labs.txnExec(Map.of("session", "B", "action", "BEGIN", "isolation", "REPEATABLE_READ"));
        labs.txnExec(Map.of("session", "B", "action", "SQL", "sql", "SELECT * FROM account WHERE id = 2"));
        String locksNow = labs.txnState().get("locks").toString();
        assertTrue(locksNow.contains("account/"), "RR 读持有行锁: " + locksNow);
        Map<String, Object> aPending = labs.txnExec(Map.of("session", "A", "action", "SQL",
                "sql", "UPDATE account SET balance = 3 WHERE id = 2"));
        if (!Boolean.TRUE.equals(aPending.get("pending"))) fail("A 应阻塞等待 B 的 S 锁: " + aPending);
        labs.txnExec(Map.of("session", "B", "action", "SQL",
                "sql", "UPDATE account SET balance = 4 WHERE id = 1"));
        // B 是牺牲者：死锁检测抛 DEADLOCK（异步轮询到结果）
        boolean deadlockSeen = false;
        String seen = "";
        for (int i = 0; i < 60 && !deadlockSeen; i++) {
            Map<String, Object> poll = labs.txnPoll();
            seen = poll.toString();
            deadlockSeen = seen.contains("DEADLOCK");
            if (!deadlockSeen && "idle".equals(state(poll, "B")) && i > 3) break;
            Thread.sleep(50);
        }
        assertTrue(deadlockSeen, "死锁被真实检测器捕获: " + seen);
        // 收尾：先回滚牺牲者 B（释放 row2，A 的挂起操作得以完成），再回滚 A
        labs.txnExec(Map.of("session", "B", "action", "ROLLBACK"));
        assertConverged("A");
        labs.txnExec(Map.of("session", "A", "action", "ROLLBACK"));
    }

    @Test
    @Order(82)
    void storageAndBufferPoolLabs() {
        Map<String, Object> st = labs.storageSetup();
        assertTrue(((Number) st.get("rowCount")).intValue() >= 2);
        assertNotNull(st.get("pages"));

        Map<String, Object> act = labs.storageAct(Map.of("table", "var_demo", "op", "insertVar",
                "id", 50, "note", "新插入的记录"));
        assertTrue(act.toString().contains("rows"), "动作后返回最新状态");

        Map<String, Object> bp = labs.bufferPoolSetup();
        assertTrue(((Number) bp.get("size")).intValue() > 0);
        Map<String, Object> read = labs.bufferPoolAct(Map.of("op", "readAll"));
        assertTrue(((Number) read.get("hits")).intValue() + ((Number) read.get("misses")).intValue() > 0);
        Map<String, Object> upd = labs.bufferPoolAct(Map.of("op", "update", "id", 1));
        assertTrue(((Number) upd.get("dirtyPages")).intValue() > 0, "更新后出现脏页");
        Map<String, Object> flush = labs.bufferPoolAct(Map.of("op", "flush"));
        assertTrue(((Number) flush.get("writebacks")).intValue() > 0, "flush 后写回");
    }

    @Test
    @Order(83)
    void perfAndJdbcLabs() {
        Map<String, Object> perf = labs.perfRun(Map.of("rows", 500, "queries", 30, "warmup", 3));
        assertEquals(2, cast(perf.get("results")).size());
        assertTrue(String.valueOf(perf.get("planSeqScan")).contains("SeqScan"));
        assertTrue(String.valueOf(perf.get("planIndexScan")).contains("IndexScan"));

        Map<String, Object> jdbc = labs.jdbcRun();
        assertTrue(cast(jdbc.get("log")).stream().anyMatch(l -> l.toString().contains("rollback")));
        assertEquals(2, cast(jdbc.get("rows")).size(), "删除 id=3 后剩两行");
    }
}
