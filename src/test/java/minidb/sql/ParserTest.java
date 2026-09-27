package minidb.sql;

import minidb.common.MiniDbException;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 递归下降语法分析器：AST 结构、优先级、错误恢复 */
class ParserTest {

    private static Object parse(String sql) {
        return Parser.parse(sql);
    }

    private static Ast.SelectStmt sel(String sql) {
        return (Ast.SelectStmt) parse(sql);
    }

    // ---------- DDL / DML ----------

    @Test
    void createTableParses() {
        Ast.CreateTableStmt c = (Ast.CreateTableStmt) parse(
                "CREATE TABLE t (id INT, name VARCHAR(20), score DOUBLE, ts BIGINT)");
        assertEquals("t", c.table());
        assertEquals(4, c.columns().size());
        assertEquals("VARCHAR", c.columns().get(1).type());
        assertEquals(20, c.columns().get(1).size());
        assertNull(c.columns().get(0).size());
    }

    @Test
    void createTablePrimaryKeyAccepted() {
        Ast.CreateTableStmt c = (Ast.CreateTableStmt) parse(
                "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10))");
        assertEquals(2, c.columns().size());
    }

    @Test
    void dropTableAndIndexParses() {
        assertEquals("t", ((Ast.DropTableStmt) parse("DROP TABLE t")).table());
        assertEquals("ix", ((Ast.DropIndexStmt) parse("DROP INDEX ix")).index());
    }

    @Test
    void createIndexParses() {
        Ast.CreateIndexStmt c = (Ast.CreateIndexStmt) parse("CREATE INDEX i1 ON emp(id)");
        assertEquals("i1", c.index());
        assertEquals("emp", c.table());
        assertEquals("id", c.column());
    }

    @Test
    void insertParses() {
        Ast.InsertStmt i = (Ast.InsertStmt) parse(
                "INSERT INTO t (b, a) VALUES (2, 1), (4, 3)");
        assertEquals("t", i.table());
        assertEquals(List.of("b", "a"), i.columns());
        assertEquals(2, i.rows().size());
    }

    @Test
    void updateDeleteParse() {
        Ast.UpdateStmt u = (Ast.UpdateStmt) parse("UPDATE t SET a = 1, b = a + 2 WHERE c > 5");
        assertEquals(2, u.sets().size());
        assertEquals("b", u.sets().get(1).column());
        assertNotNull(u.where());
        Ast.DeleteStmt d = (Ast.DeleteStmt) parse("DELETE FROM t");
        assertNull(d.where());
    }

    // ---------- SELECT ----------

    @Test
    void selectStarAndItems() {
        Ast.SelectStmt s = sel("SELECT * FROM t");
        assertTrue(s.items().get(0).star());
        s = sel("SELECT a AS x, b FROM t");
        assertEquals("x", s.items().get(0).alias());
        assertNull(s.items().get(1).alias());
    }

    @Test
    void wherePrecedenceAndOrNot() {
        Ast.SelectStmt s = sel("SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3");
        Ast.BinOp or = (Ast.BinOp) s.where();
        assertEquals("OR", or.op());
        Ast.BinOp and = (Ast.BinOp) or.right();
        assertEquals("AND", and.op()); // AND 优先级高于 OR
    }

    @Test
    void arithmeticPrecedence() {
        Ast.SelectStmt s = sel("SELECT 1 + 2 * 3 - 4 / 2 FROM t");
        // 左结合：((1 + 2*3) - 4/2)
        Ast.BinOp top = (Ast.BinOp) s.items().get(0).expr();
        assertEquals("-", top.op());
        Ast.BinOp add = (Ast.BinOp) top.left();
        assertEquals("+", add.op());
        Ast.BinOp mul = (Ast.BinOp) add.right();
        assertEquals("*", mul.op());
        Ast.BinOp div = (Ast.BinOp) top.right();
        assertEquals("/", div.op());
    }

    @Test
    void notUnary() {
        Ast.SelectStmt s = sel("SELECT * FROM t WHERE NOT a = 1");
        Ast.UnaryOp u = (Ast.UnaryOp) s.where();
        assertEquals("NOT", u.op());
    }

    @Test
    void likeInBetweenIsNull() {
        Ast.SelectStmt s = sel("SELECT * FROM t WHERE a LIKE 'x%' AND b IN (1, 2) "
                + "AND c BETWEEN 1 AND 9 AND d IS NULL AND e IS NOT NULL");
        List<Ast.Expr> cs = flatAnd(s.where());
        assertEquals(5, cs.size());
        assertEquals("x%", ((Ast.LikeOp) cs.get(0)).pattern());
        assertEquals(2, ((Ast.InListOp) cs.get(1)).items().size());
        assertNotNull(((Ast.BetweenOp) cs.get(2)).low());
        assertFalse(((Ast.IsNullOp) cs.get(3)).negated());
        assertTrue(((Ast.IsNullOp) cs.get(4)).negated());
    }

    @Test
    void notLikeNotInNotBetween() {
        Ast.SelectStmt s = sel("SELECT * FROM t WHERE a NOT LIKE 'x' AND b NOT IN (1) AND c NOT BETWEEN 1 AND 2");
        List<Ast.Expr> cs = flatAnd(s.where());
        assertTrue(((Ast.LikeOp) cs.get(0)).negated());
        assertTrue(((Ast.InListOp) cs.get(1)).negated());
        assertTrue(((Ast.BetweenOp) cs.get(2)).negated());
    }

    /** AND 链左结合展平 */
    private static List<Ast.Expr> flatAnd(Ast.Expr e) {
        List<Ast.Expr> out = new java.util.ArrayList<>();
        while (e instanceof Ast.BinOp b && b.op().equals("AND")) {
            flatAndInto(b.left(), out);
            e = b.right();
        }
        flatAndInto(e, out);
        return out;
    }

    private static void flatAndInto(Ast.Expr e, List<Ast.Expr> out) {
        if (e instanceof Ast.BinOp b && b.op().equals("AND")) {
            flatAndInto(b.left(), out);
            flatAndInto(b.right(), out);
        } else out.add(e);
    }

    @Test
    void qualifiedColumns() {
        Ast.SelectStmt s = sel("SELECT t.a, u.b FROM t, u WHERE t.a = u.b");
        Ast.ColRef first = (Ast.ColRef) s.items().get(0).expr();
        assertEquals("t", first.table());
        assertEquals("a", first.column());
    }

    @Test
    void joinSyntax() {
        Ast.SelectStmt s = sel("SELECT * FROM a INNER JOIN b ON a.id = b.id LEFT JOIN c ON b.id = c.id");
        Ast.Join left = (Ast.Join) s.from();
        assertEquals("LEFT", left.type());
        Ast.Join inner = (Ast.Join) left.left();
        assertEquals("INNER", inner.type());
    }

    @Test
    void leftOuterJoin() {
        Ast.SelectStmt s = sel("SELECT * FROM a LEFT OUTER JOIN b ON a.x = b.x");
        assertEquals("LEFT", ((Ast.Join) s.from()).type());
    }

    @Test
    void aggregates() {
        Ast.SelectStmt s = sel("SELECT COUNT(*), SUM(x), AVG(y), MIN(z), MAX(w) FROM t");
        assertEquals(Ast.FuncCall.class, s.items().get(0).expr().getClass());
        assertTrue(((Ast.FuncCall) s.items().get(0).expr()).star());
        assertEquals("SUM", ((Ast.FuncCall) s.items().get(1).expr()).name());
    }

    @Test
    void groupByHavingOrderByLimit() {
        Ast.SelectStmt s = sel("SELECT a, COUNT(*) FROM t GROUP BY a HAVING COUNT(*) > 1 "
                + "ORDER BY a DESC, COUNT(*) LIMIT 5 OFFSET 2");
        assertEquals(1, s.groupBy().size());
        assertNotNull(s.having());
        assertEquals(2, s.orderBy().size());
        assertTrue(s.orderBy().get(0).desc());
        assertFalse(s.orderBy().get(1).desc());
        assertEquals(5L, s.limit());
        assertEquals(2L, s.offset());
    }

    @Test
    void distinct() {
        assertTrue(sel("SELECT DISTINCT a FROM t").distinct());
    }

    @Test
    void subqueries() {
        Ast.SelectStmt s = sel("SELECT * FROM t WHERE a IN (SELECT x FROM u) AND "
                + "b = (SELECT MAX(y) FROM u) AND EXISTS (SELECT 1 FROM v WHERE v.k = t.k)");
        List<Ast.Expr> cs = flatAnd(s.where());
        assertEquals(3, cs.size());
        Ast.InQueryOp in = (Ast.InQueryOp) cs.get(0);
        assertNotNull(in.query());
        Ast.BinOp eq = (Ast.BinOp) cs.get(1);
        Ast.ScalarQueryOp scalar = (Ast.ScalarQueryOp) eq.right();
        assertEquals("MAX", ((Ast.FuncCall) scalar.query().items().get(0).expr()).name());
        Ast.ExistsOp ex = (Ast.ExistsOp) cs.get(2);
        assertNotNull(ex.query());
    }

    @Test
    void negativeNumbers() {
        Ast.SelectStmt s = sel("SELECT -1, -2.5 FROM t WHERE a = -3");
        Ast.UnaryOp neg = (Ast.UnaryOp) s.items().get(0).expr();
        assertEquals("-", neg.op());
    }

    @Test
    void stringEscapesInSql() {
        Ast.SelectStmt s = sel("SELECT 'it''s' FROM t");
        Ast.Literal lit = (Ast.Literal) s.items().get(0).expr();
        assertEquals("it's", lit.value());
    }

    // ---------- 错误注入 ----------

    @Test
    void missingFromThrows() {
        assertThrows(MiniDbException.class, () -> parse("SELECT a WHERE b"));
    }

    @Test
    void badKeywordThrows() {
        assertThrows(MiniDbException.class, () -> parse("FROB t"));
    }

    @Test
    void missingParenThrows() {
        assertThrows(MiniDbException.class, () -> parse("SELECT * FROM t WHERE a IN (1, 2"));
    }

    @Test
    void garbageAfterStatementThrows() {
        assertThrows(MiniDbException.class, () -> parse("SELECT 1 2"));
    }

    @Test
    void trailingSemicolonAccepted() {
        assertDoesNotThrow(() -> parse("SELECT 1 FROM t;"));
    }

    @Test
    void emptyInputThrows() {
        assertThrows(MiniDbException.class, () -> parse("   "));
    }

    @Test
    void aliasWithoutAs() {
        Ast.SelectStmt s = sel("SELECT a x FROM t b");
        assertEquals("x", s.items().get(0).alias());
        assertEquals("b", ((Ast.NamedTable) s.from()).alias());
    }
}
