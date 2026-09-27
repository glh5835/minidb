package minidb.exec;

import minidb.common.MiniDbException;
import minidb.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 端到端 SQL 执行：DDL/DML/SELECT 全语法 + 索引维护 + 故障注入 */
class ExecutorSqlTest {
    @TempDir
    Path dir;

    private Database db;
    private Executor ex;

    @BeforeEach
    void setUp() {
        db = Database.open(dir.resolve("t.db"), 512);
        ex = new Executor(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Executor.Result q(String sql) {
        return ex.execute(sql);
    }

    private void setupEmpDept() {
        q("CREATE TABLE emp (id INT, name VARCHAR(20), dept INT, salary DOUBLE)");
        q("CREATE TABLE dept (id INT, dname VARCHAR(20))");
        q("INSERT INTO emp VALUES (1,'alice',10,100.0),(2,'bob',20,80.0),(3,'carol',10,90.0),(4,'dave',30,70.0)");
        q("INSERT INTO dept VALUES (10,'eng'),(20,'sales'),(40,'hr')");
    }

    // ---------- DDL ----------

    @Test
    void createAndDropTable() {
        q("CREATE TABLE t (a INT)");
        assertTrue(db.hasTable("t"));
        q("DROP TABLE t");
        assertFalse(db.hasTable("t"));
    }

    @Test
    void duplicateTableThrows() {
        q("CREATE TABLE t (a INT)");
        assertThrows(MiniDbException.class, () -> q("CREATE TABLE t (b INT)"));
    }

    @Test
    void dropMissingTableThrows() {
        assertThrows(MiniDbException.class, () -> q("DROP TABLE nope"));
    }

    @Test
    void badTypeThrows() {
        assertThrows(MiniDbException.class, () -> q("CREATE TABLE t (a BLOB)"));
    }

    // ---------- INSERT ----------

    @Test
    void insertAndSelect() {
        setupEmpDept();
        assertEquals(4, q("SELECT * FROM emp").rowCount());
    }

    @Test
    void insertColumnReorder() {
        q("CREATE TABLE t (a INT, b VARCHAR(10))");
        q("INSERT INTO t (b, a) VALUES ('x', 1)");
        Executor.Result r = q("SELECT * FROM t");
        assertArrayEquals(new Object[]{1, "x"}, r.rows().get(0));
    }

    @Test
    void insertWrongArityThrows() {
        q("CREATE TABLE t (a INT, b INT)");
        assertThrows(MiniDbException.class, () -> q("INSERT INTO t VALUES (1)"));
        assertThrows(MiniDbException.class, () -> q("INSERT INTO t VALUES (1,2,3)"));
    }

    @Test
    void insertTypeMismatchThrows() {
        q("CREATE TABLE t (a INT)");
        assertThrows(MiniDbException.class, () -> q("INSERT INTO t VALUES ('x')"));
    }

    @Test
    void insertIntoMissingTableThrows() {
        assertThrows(MiniDbException.class, () -> q("INSERT INTO nope VALUES (1)"));
    }

    @Test
    void varcharOverflowThrows() {
        q("CREATE TABLE t (s VARCHAR(3))");
        assertThrows(MiniDbException.class, () -> q("INSERT INTO t VALUES ('abcd')"));
    }

    // ---------- SELECT / WHERE ----------

    @Test
    void whereComparisons() {
        setupEmpDept();
        assertEquals(2, q("SELECT * FROM emp WHERE salary >= 90").rowCount());
        assertEquals(2, q("SELECT * FROM emp WHERE salary > 80 AND dept = 10").rowCount());
        assertEquals(4, q("SELECT * FROM emp WHERE salary > 0 OR dept = 999").rowCount());
        assertEquals(2, q("SELECT * FROM emp WHERE NOT dept = 10").rowCount());
        assertEquals(1, q("SELECT * FROM emp WHERE name = 'bob'").rowCount());
        assertEquals(2, q("SELECT * FROM emp WHERE id != 1 AND id != 2").rowCount());
    }

    @Test
    void whereArithmetic() {
        setupEmpDept();
        assertEquals(1, q("SELECT * FROM emp WHERE salary * 2 > 180").rowCount());
        assertEquals(1, q("SELECT * FROM emp WHERE id + 1 = 3").rowCount());
    }

    @Test
    void whereLike() {
        setupEmpDept();
        assertEquals(1, q("SELECT * FROM emp WHERE name LIKE 'a%'").rowCount());
        assertEquals(3, q("SELECT * FROM emp WHERE name LIKE '%a%'").rowCount()); // alice, carol, dave
        assertEquals(1, q("SELECT * FROM emp WHERE name LIKE '_ob'").rowCount());
        assertEquals(4, q("SELECT * FROM emp WHERE name LIKE '%'").rowCount());
    }

    @Test
    void whereInAndBetween() {
        setupEmpDept();
        assertEquals(3, q("SELECT * FROM emp WHERE dept IN (10, 20)").rowCount());
        assertEquals(2, q("SELECT * FROM emp WHERE dept NOT IN (10)").rowCount());
        assertEquals(3, q("SELECT * FROM emp WHERE salary BETWEEN 80 AND 100").rowCount());
        assertEquals(1, q("SELECT * FROM emp WHERE salary NOT BETWEEN 70 AND 90").rowCount());
    }

    @Test
    void projectAndExpressions() {
        setupEmpDept();
        Executor.Result r = q("SELECT name, salary * 2 AS double_pay FROM emp WHERE id = 1");
        assertEquals(1, r.rowCount());
        assertArrayEquals(new Object[]{"alice", 200.0}, r.rows().get(0));
        assertEquals("double_pay", r.columns().get(1));
    }

    @Test
    void selectDistinct() {
        setupEmpDept();
        assertEquals(3, q("SELECT DISTINCT dept FROM emp").rowCount());
        assertEquals(4, q("SELECT DISTINCT salary FROM emp").rowCount());
    }

    @Test
    void orderBy() {
        setupEmpDept();
        Executor.Result r = q("SELECT name FROM emp ORDER BY salary DESC");
        assertEquals("alice", r.rows().get(0)[0]);
        assertEquals("dave", r.rows().get(3)[0]);
        r = q("SELECT name FROM emp ORDER BY dept ASC, salary DESC");
        assertEquals("alice", r.rows().get(0)[0]); // dept 10, salary 100
        assertEquals("carol", r.rows().get(1)[0]);
    }

    @Test
    void orderByAlias() {
        setupEmpDept();
        Executor.Result r = q("SELECT name, salary * 2 AS d FROM emp ORDER BY d DESC");
        assertEquals("alice", r.rows().get(0)[0]);
    }

    @Test
    void limitOffset() {
        setupEmpDept();
        assertEquals(2, q("SELECT * FROM emp LIMIT 2").rowCount());
        Executor.Result r = q("SELECT id FROM emp ORDER BY id LIMIT 2 OFFSET 1");
        assertEquals(2, r.rowCount());
        assertEquals(2, r.rows().get(0)[0]);
        assertEquals(3, r.rows().get(1)[0]);
    }

    // ---------- JOIN ----------

    @Test
    void innerJoin() {
        setupEmpDept();
        Executor.Result r = q("SELECT emp.name, dept.dname FROM emp INNER JOIN dept ON emp.dept = dept.id");
        assertEquals(3, r.rowCount()); // emp.dept 10,10,20 匹配
    }

    @Test
    void leftJoin() {
        setupEmpDept();
        Executor.Result r = q("SELECT emp.name, dept.dname FROM emp LEFT JOIN dept ON emp.dept = dept.id");
        assertEquals(4, r.rowCount()); // dave 的 30 无匹配 → NULL
        long nullDname = r.rows().stream().filter(row -> row[1] == null).count();
        assertEquals(1, nullDname);
    }

    @Test
    void threeWayJoinOrder() {
        setupEmpDept();
        Executor.Result r = q("SELECT emp.name FROM emp INNER JOIN dept ON emp.dept = dept.id "
                + "INNER JOIN dept d2 ON d2.id = dept.id");
        assertEquals(3, r.rowCount());
    }

    @Test
    void joinWithWhere() {
        setupEmpDept();
        Executor.Result r = q("SELECT emp.name FROM emp JOIN dept ON emp.dept = dept.id "
                + "WHERE dept.dname = 'eng' ORDER BY emp.name");
        assertEquals(List.of("alice", "carol"), r.rows().stream().map(x -> x[0]).toList());
    }

    // ---------- 聚合 / GROUP BY / HAVING ----------

    @Test
    void globalAggregates() {
        setupEmpDept();
        Executor.Result r = q("SELECT COUNT(*), SUM(salary), AVG(salary), MIN(salary), MAX(salary) FROM emp");
        Object[] row = r.rows().get(0);
        assertEquals(4, row[0]);
        assertEquals(340.0, (Double) row[1], 1e-9);
        assertEquals(85.0, (Double) row[2], 1e-9);
        assertEquals(70.0, row[3]);
        assertEquals(100.0, row[4]);
    }

    @Test
    void countStarVsCountColumn() {
        setupEmpDept();
        assertEquals(4, (int) q("SELECT COUNT(*) FROM emp").rows().get(0)[0]);
    }

    @Test
    void aggregateOnEmptyTable() {
        q("CREATE TABLE t (a INT)");
        Executor.Result r = q("SELECT COUNT(*), SUM(a), MIN(a) FROM t");
        assertArrayEquals(new Object[]{0, null, null}, r.rows().get(0));
    }

    @Test
    void groupByAggregates() {
        setupEmpDept();
        Executor.Result r = q("SELECT dept, COUNT(*), AVG(salary) FROM emp GROUP BY dept ORDER BY dept");
        assertEquals(3, r.rowCount());
        assertArrayEquals(new Object[]{10, 2, 95.0}, r.rows().get(0));
        assertArrayEquals(new Object[]{20, 1, 80.0}, r.rows().get(1));
        assertArrayEquals(new Object[]{30, 1, 70.0}, r.rows().get(2));
    }

    @Test
    void groupByHaving() {
        setupEmpDept();
        Executor.Result r = q("SELECT dept, COUNT(*) AS n FROM emp GROUP BY dept HAVING COUNT(*) > 1");
        assertEquals(1, r.rowCount());
        assertEquals(10, r.rows().get(0)[0]);
    }

    @Test
    void groupByExpression() {
        setupEmpDept();
        Executor.Result r = q("SELECT dept * 10 AS d10, COUNT(*) FROM emp GROUP BY dept * 10 HAVING d10 >= 100");
        // HAVING 里用别名（组表达式 100/200/300）
        assertEquals(3, r.rowCount());
    }

    @Test
    void nonGroupedColumnThrows() {
        setupEmpDept();
        assertThrows(MiniDbException.class,
                () -> q("SELECT name, COUNT(*) FROM emp GROUP BY dept"));
    }

    // ---------- 子查询 ----------

    @Test
    void inSubquery() {
        setupEmpDept();
        Executor.Result r = q("SELECT name FROM emp WHERE dept IN (SELECT id FROM dept) ORDER BY name");
        assertEquals(3, r.rowCount()); // dave 的 30 不在
        assertEquals("alice", r.rows().get(0)[0]);
    }

    @Test
    void notInSubquery() {
        setupEmpDept();
        Executor.Result r = q("SELECT name FROM emp WHERE dept NOT IN (SELECT id FROM dept)");
        assertEquals(1, r.rowCount());
        assertEquals("dave", r.rows().get(0)[0]);
    }

    @Test
    void scalarSubquery() {
        setupEmpDept();
        Executor.Result r = q("SELECT name FROM emp WHERE salary = (SELECT MAX(salary) FROM emp)");
        assertEquals(1, r.rowCount());
        assertEquals("alice", r.rows().get(0)[0]);
    }

    @Test
    void existsSubquery() {
        setupEmpDept();
        Executor.Result r = q("SELECT dname FROM dept WHERE EXISTS "
                + "(SELECT 1 FROM emp WHERE emp.dept = dept.id) ORDER BY dname");
        assertEquals(2, r.rowCount());
    }

    @Test
    void correlatedScalarSubquery() {
        setupEmpDept();
        Executor.Result r = q("SELECT empOuter.name FROM emp empOuter WHERE empOuter.salary >= "
                + "(SELECT AVG(salary) FROM emp WHERE emp.dept = empOuter.dept) ORDER BY empOuter.name");
        // 每部门平均：10→95，20→80，30→70；高于等于者：alice(100)、bob(80)、dave(70)，carol(90)<95 不入选
        assertEquals(3, r.rowCount());
    }

    // ---------- UPDATE / DELETE ----------

    @Test
    void updateRows() {
        setupEmpDept();
        q("UPDATE emp SET salary = salary + 10 WHERE dept = 10");
        Executor.Result r = q("SELECT salary FROM emp WHERE dept = 10 ORDER BY id");
        assertEquals(110.0, r.rows().get(0)[0]);
        assertEquals(100.0, r.rows().get(1)[0]);
    }

    @Test
    void updateMultipleAssignments() {
        setupEmpDept();
        q("UPDATE emp SET name = 'alina', salary = 1 WHERE id = 1");
        Executor.Result r = q("SELECT name, salary FROM emp WHERE id = 1");
        assertEquals("alina", r.rows().get(0)[0]);
        assertEquals(1.0, r.rows().get(0)[1]);
    }

    @Test
    void deleteRows() {
        setupEmpDept();
        q("DELETE FROM emp WHERE dept = 10");
        assertEquals(2, q("SELECT COUNT(*) FROM emp").rows().get(0)[0]);
    }

    @Test
    void deleteAllRows() {
        setupEmpDept();
        q("DELETE FROM emp");
        assertEquals(0, q("SELECT COUNT(*) FROM emp").rows().get(0)[0]);
        assertEquals(0, q("SELECT * FROM emp").rowCount());
    }

    // ---------- 索引集成 ----------

    @Test
    void indexMaintainedAcrossDml() {
        setupEmpDept();
        q("CREATE INDEX idx_id ON emp(id)");
        q("INSERT INTO emp VALUES (9, 'zed', 40, 50.0)");
        q("UPDATE emp SET id = 8 WHERE name = 'zed'");
        q("DELETE FROM emp WHERE id = 2");
        Executor.Result r = q("SELECT name FROM emp WHERE id = 8");
        assertEquals(1, r.rowCount());
        assertEquals("zed", r.rows().get(0)[0]);
        assertEquals(0, q("SELECT name FROM emp WHERE id = 2").rowCount());
        assertEquals(0, q("SELECT name FROM emp WHERE id = 9").rowCount());
    }

    @Test
    void indexRangeQuery() {
        setupEmpDept();
        q("CREATE INDEX idx_id ON emp(id)"); // 索引列仅支持 INT/BIGINT
        assertEquals(4, q("SELECT COUNT(*) FROM emp WHERE id >= 1").rows().get(0)[0]);
        assertEquals(2, q("SELECT COUNT(*) FROM emp WHERE id < 3").rows().get(0)[0]);
        assertEquals(1, q("SELECT COUNT(*) FROM emp WHERE id = 2").rows().get(0)[0]);
    }

    @Test
    void duplicateKeyRejectedOnUniqueIndex() {
        setupEmpDept();
        assertThrows(MiniDbException.class, () -> q("CREATE INDEX idx_dept ON emp(dept)"));
    }

    // ---------- 持久化 ----------

    @Test
    void sqlDataSurvivesReopen() {
        setupEmpDept();
        q("CREATE INDEX idx_id ON emp(id)");
        db.flush();
        db.close();
        db = Database.open(dir.resolve("t.db"), 512);
        ex = new Executor(db);
        assertEquals(4, q("SELECT COUNT(*) FROM emp").rows().get(0)[0]);
        assertEquals(1, q("SELECT name FROM emp WHERE id = 3").rowCount());
    }

    // ---------- NULL 语义 ----------

    @Test
    void scalarSubqueryEmptyGivesNullAndIsNullWorks() {
        setupEmpDept();
        Executor.Result r = q("SELECT name FROM emp WHERE (SELECT MAX(salary) FROM emp WHERE dept = 99) IS NULL");
        assertEquals(4, r.rowCount());
    }

    @Test
    void nullComparisonIsFalse() {
        setupEmpDept();
        // 标量子查询返回 NULL 后与任何值比较 → false
        assertEquals(0, q("SELECT name FROM emp WHERE "
                + "(SELECT MAX(salary) FROM emp WHERE dept = 99) > 0").rowCount());
    }

    // ---------- 错误注入 ----------

    @Test
    void ambiguousColumnThrows() {
        setupEmpDept();
        assertThrows(MiniDbException.class, () -> q("SELECT id FROM emp, dept WHERE id = 1"));
    }

    @Test
    void unknownColumnThrows() {
        setupEmpDept();
        assertThrows(MiniDbException.class, () -> q("SELECT nope FROM emp"));
    }

    @Test
    void unknownTableThrows() {
        assertThrows(MiniDbException.class, () -> q("SELECT * FROM nope"));
    }

    @Test
    void divisionSemantics() {
        q("CREATE TABLE t (a INT, b DOUBLE)");
        q("INSERT INTO t VALUES (7, 2.0), (1, 4.0)");
        Executor.Result r = q("SELECT a / 2, a / b FROM t ORDER BY a");
        assertEquals(0, r.rows().get(0)[0]); // 1/2 整数除
        assertEquals(0.25, r.rows().get(0)[1]);
        assertEquals(3, r.rows().get(1)[0]); // 7/2 整数除
        assertEquals(3.5, r.rows().get(1)[1]);
        // 除零 → NULL
        r = q("SELECT 1 / 0 FROM t WHERE a = 1");
        assertNull(r.rows().get(0)[0]);
    }

    @Test
    void selectWithoutFrom() {
        Executor.Result r = q("SELECT 1 + 2 AS x, 'hi' AS s");
        assertEquals(1, r.rowCount());
        assertArrayEquals(new Object[]{3, "hi"}, r.rows().get(0));
    }

    @Test
    void mixedJoinAndCommaCartesian() {
        setupEmpDept();
        // JOIN 之后再接逗号笛卡尔积（README 宣称支持的形式）
        Executor.Result r = q("SELECT e.name, d.dname FROM emp e INNER JOIN dept d ON e.dept = d.id, dept d2 "
                + "WHERE d2.id = 40 ORDER BY e.name LIMIT 1");
        assertEquals(1, r.rowCount());
        assertEquals("alice", r.rows().get(0)[0]);
    }

    @Test
    void duplicateUnaliasedTableThrowsClearError() {
        setupEmpDept();
        // 同表无别名引用两次：应报"被引用多次"而不是"找不到关系 null"
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> q("SELECT * FROM emp, emp"));
        assertTrue(e.getMessage().contains("被引用多次"), "实际消息: " + e.getMessage());
        // 带别名的自连接应正常
        q("SELECT a.id FROM emp a, emp b WHERE a.id = b.id");
    }
}
