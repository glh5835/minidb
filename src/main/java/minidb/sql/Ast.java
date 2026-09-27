package minidb.sql;

import java.util.List;

/** SQL AST。所有节点为不可变 record。 */
public final class Ast {
    private Ast() {}

    // ================= 表达式 =================

    public sealed interface Expr permits Literal, ColRef, BinOp, UnaryOp, LikeOp, InListOp,
            InQueryOp, ExistsOp, ScalarQueryOp, BetweenOp, IsNullOp, FuncCall {}

    /** 字面量：Integer / Long / Double / String / null（SQL NULL） */
    public record Literal(Object value) implements Expr {}

    /** 列引用；table 可为 null（不限定） */
    public record ColRef(String table, String column) implements Expr {}

    /** op: + - * / % = != < <= > >= AND OR */
    public record BinOp(String op, Expr left, Expr right) implements Expr {}

    /** op: NOT（前缀）或 -（负号） */
    public record UnaryOp(String op, Expr operand) implements Expr {}

    public record LikeOp(Expr operand, String pattern, boolean negated) implements Expr {}

    public record InListOp(Expr operand, List<Expr> items, boolean negated) implements Expr {}

    public record InQueryOp(Expr operand, SelectStmt query, boolean negated) implements Expr {}

    public record ExistsOp(SelectStmt query, boolean negated) implements Expr {}

    public record ScalarQueryOp(SelectStmt query) implements Expr {}

    public record BetweenOp(Expr operand, Expr low, Expr high, boolean negated) implements Expr {}

    public record IsNullOp(Expr operand, boolean negated) implements Expr {}

    /** 聚合函数调用：name COUNT/SUM/AVG/MIN/MAX；star=true 表示 COUNT(*)；arg 为 null 当 star */
    public record FuncCall(String name, Expr arg, boolean star) implements Expr {}

    // ================= SELECT =================

    /** 查询项：* 或 expr [AS alias] */
    public record SelectItem(Expr expr, String alias, boolean star) {}

    public record OrderItem(Expr expr, boolean desc) {}

    /** FROM 项：单表或连接 */
    public sealed interface TableRef permits NamedTable, Join {
        String leftmostTable();
    }

    public record NamedTable(String name, String alias) implements TableRef {
        @Override
        public String leftmostTable() { return name; }
    }

    /** type: INNER / LEFT */
    public record Join(TableRef left, TableRef right, String type, Expr on) implements TableRef {
        @Override
        public String leftmostTable() { return left.leftmostTable(); }
    }

    /**
     * SELECT 语句（含子查询）。
     * from 为 null 表示无 FROM（如 SELECT 1+1）。
     */
    public record SelectStmt(
            boolean distinct,
            List<SelectItem> items,
            TableRef from,
            Expr where,
            List<Expr> groupBy,
            Expr having,
            List<OrderItem> orderBy,
            Long limit,
            Long offset) {}

    // ================= DML / DDL =================

    public record ColumnDef(String name, String type, Integer size) {}

    public record CreateTableStmt(String table, List<ColumnDef> columns, boolean ifNotExists) {}

    public record DropTableStmt(String table) {}

    public record CreateIndexStmt(String index, String table, String column) {}

    public record DropIndexStmt(String index) {}

    public record InsertStmt(String table, List<String> columns, List<List<Expr>> rows) {}

    public record UpdateStmt(String table, List<Assign> sets, Expr where) {
        public record Assign(String column, Expr value) {}
    }

    public record DeleteStmt(String table, Expr where) {}
}
