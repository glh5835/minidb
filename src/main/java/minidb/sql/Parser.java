package minidb.sql;

import minidb.common.MiniDbException;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写递归下降语法分析器（不使用 ANTLR）。
 *
 * 表达式优先级（低→高）：
 *   OR → AND → NOT → 比较/IN/LIKE/BETWEEN/IS → + - → * / % → 一元负号 → 原子
 */
public final class Parser {
    private final List<Token> toks;
    private int i;

    public Parser(String sql) {
        this.toks = new Lexer(sql).tokenize();
    }

    /** 解析一条语句（允许尾随分号）。 */
    public Object parseStatement() {
        Object s = statement();
        accept(Token.Type.SEMI);
        expect(Token.Type.EOF);
        return s;
    }

    public static Object parse(String sql) {
        return new Parser(sql).parseStatement();
    }

    // ---------- 语句 ----------

    private Object statement() {
        Token t = peek();
        return switch (t.type) {
            case SELECT -> selectStatement();
            case INSERT -> insertStatement();
            case UPDATE -> updateStatement();
            case DELETE -> deleteStatement();
            case CREATE -> createStatement();
            case DROP -> dropStatement();
            case BEGIN, COMMIT, ROLLBACK -> {
                next();
                yield t.type;
            }
            default -> throw Token.err("期望语句开头（SELECT/INSERT/UPDATE/DELETE/CREATE/DROP），得到 " + t.text, t.pos);
        };
    }

    private Ast.SelectStmt selectStatement() {
        expect(Token.Type.SELECT);
        boolean distinct = accept(Token.Type.DISTINCT);
        List<Ast.SelectItem> items = selectItems();
        Ast.TableRef from = null;
        if (accept(Token.Type.FROM)) from = tableRef();
        Ast.Expr where = accept(Token.Type.WHERE) ? expr() : null;
        List<Ast.Expr> groupBy = null;
        if (accept(Token.Type.GROUP)) {
            expect(Token.Type.BY);
            groupBy = exprList();
        }
        Ast.Expr having = accept(Token.Type.HAVING) ? expr() : null;
        List<Ast.OrderItem> orderBy = null;
        if (accept(Token.Type.ORDER)) {
            expect(Token.Type.BY);
            orderBy = new ArrayList<>();
            do {
                Ast.Expr e = expr();
                boolean desc = false;
                if (accept(Token.Type.DESC)) desc = true;
                else accept(Token.Type.ASC);
                orderBy.add(new Ast.OrderItem(e, desc));
            } while (accept(Token.Type.COMMA));
        }
        Long limit = null, offset = null;
        if (accept(Token.Type.LIMIT)) {
            limit = longLiteral();
            if (accept(Token.Type.OFFSET)) offset = longLiteral();
        }
        if (from == null && (where != null || groupBy != null || having != null || orderBy != null))
            throw Token.err("无 FROM 的 SELECT 不能带 WHERE/GROUP BY/HAVING/ORDER BY", peek().pos);
        return new Ast.SelectStmt(distinct, items, from, where, groupBy, having, orderBy, limit, offset);
    }

    private List<Ast.SelectItem> selectItems() {
        List<Ast.SelectItem> out = new ArrayList<>();
        do {
            if (peek().is(Token.Type.STAR)) {
                next();
                out.add(new Ast.SelectItem(null, null, true));
            } else {
                Ast.Expr e = expr();
                String alias = null;
                if (accept(Token.Type.AS)) alias = identifier();
                else if (peek().is(Token.Type.IDENT)) alias = next().text; // 隐式别名
                out.add(new Ast.SelectItem(e, alias, false));
            }
        } while (accept(Token.Type.COMMA));
        return out;
    }

    /** FROM：cross 逗号视为 INNER JOIN ON TRUE 的简写不在此实现，仅 JOIN 语法 */
    private Ast.TableRef tableRef() {
        Ast.TableRef left = namedTable();
        while (true) {
            if (accept(Token.Type.COMMA)) {
                left = new Ast.Join(left, namedTable(), "INNER", null); // 逗号 = 笛卡尔积
                continue;
            }
            if (accept(Token.Type.INNER) || peek().is(Token.Type.JOIN) || peek().is(Token.Type.LEFT)) {
                boolean leftJoin = accept(Token.Type.LEFT);
                if (leftJoin) {
                    accept(Token.Type.OUTER); // LEFT [OUTER] JOIN
                }
                expect(Token.Type.JOIN);
                Ast.TableRef right = namedTable();
                expect(Token.Type.ON);
                Ast.Expr on = expr();
                left = new Ast.Join(left, right, leftJoin ? "LEFT" : "INNER", on);
            } else break;
        }
        return left;
    }

    private Ast.NamedTable namedTable() {
        String name = identifier();
        String alias = null;
        if (accept(Token.Type.AS)) alias = identifier();
        else if (peek().is(Token.Type.IDENT)) alias = next().text;
        return new Ast.NamedTable(name, alias);
    }

    private Ast.InsertStmt insertStatement() {
        expect(Token.Type.INSERT);
        expect(Token.Type.INTO);
        String table = identifier();
        List<String> columns = null;
        if (accept(Token.Type.LPAREN)) {
            columns = new ArrayList<>();
            do { columns.add(identifier()); } while (accept(Token.Type.COMMA));
            expect(Token.Type.RPAREN);
        }
        expect(Token.Type.VALUES);
        List<List<Ast.Expr>> rows = new ArrayList<>();
        do {
            expect(Token.Type.LPAREN);
            List<Ast.Expr> row = new ArrayList<>();
            do { row.add(expr()); } while (accept(Token.Type.COMMA));
            expect(Token.Type.RPAREN);
            rows.add(row);
        } while (accept(Token.Type.COMMA));
        return new Ast.InsertStmt(table, columns, rows);
    }

    private Ast.UpdateStmt updateStatement() {
        expect(Token.Type.UPDATE);
        String table = identifier();
        expect(Token.Type.SET);
        List<Ast.UpdateStmt.Assign> sets = new ArrayList<>();
        do {
            String col = identifier();
            expect(Token.Type.EQ);
            sets.add(new Ast.UpdateStmt.Assign(col, expr()));
        } while (accept(Token.Type.COMMA));
        Ast.Expr where = accept(Token.Type.WHERE) ? expr() : null;
        return new Ast.UpdateStmt(table, sets, where);
    }

    private Ast.DeleteStmt deleteStatement() {
        expect(Token.Type.DELETE);
        expect(Token.Type.FROM);
        String table = identifier();
        Ast.Expr where = accept(Token.Type.WHERE) ? expr() : null;
        return new Ast.DeleteStmt(table, where);
    }

    private Object createStatement() {
        expect(Token.Type.CREATE);
        if (accept(Token.Type.TABLE)) {
            String table = identifier();
            expect(Token.Type.LPAREN);
            List<Ast.ColumnDef> cols = new ArrayList<>();
            do {
                String cname = identifier();
                String type = typeToken();
                Integer size = null;
                if (accept(Token.Type.LPAREN)) {
                    size = (int) longLiteral();
                    expect(Token.Type.RPAREN);
                }
                cols.add(new Ast.ColumnDef(cname, type, size));
                if (peek().is(Token.Type.PRIMARY)) { // PRIMARY KEY：记录位置用不上，仅接受
                    next();
                    expect(Token.Type.KEY);
                }
            } while (accept(Token.Type.COMMA));
            expect(Token.Type.RPAREN);
            return new Ast.CreateTableStmt(table, cols);
        }
        expect(Token.Type.INDEX);
        String index = identifier();
        expect(Token.Type.ON);
        String table = identifier();
        expect(Token.Type.LPAREN);
        String column = identifier();
        expect(Token.Type.RPAREN);
        return new Ast.CreateIndexStmt(index, table, column);
    }

    private String typeToken() {
        Token t = next();
        return switch (t.type) {
            case INT, INTEGER -> "INT";
            case BIGINT, LONG -> "BIGINT";
            case DOUBLE, FLOAT -> "DOUBLE";
            case VARCHAR, STRING_TYPE -> "VARCHAR";
            default -> throw Token.err("期望类型名，得到 " + t.text, t.pos);
        };
    }

    private Object dropStatement() {
        expect(Token.Type.DROP);
        if (accept(Token.Type.TABLE)) return new Ast.DropTableStmt(identifier());
        expect(Token.Type.INDEX);
        return new Ast.DropIndexStmt(identifier());
    }

    // ---------- 表达式 ----------

    private Ast.Expr expr() {
        return orExpr();
    }

    private Ast.Expr orExpr() {
        Ast.Expr left = andExpr();
        while (accept(Token.Type.OR)) left = new Ast.BinOp("OR", left, andExpr());
        return left;
    }

    private Ast.Expr andExpr() {
        Ast.Expr left = notExpr();
        while (accept(Token.Type.AND)) left = new Ast.BinOp("AND", left, notExpr());
        return left;
    }

    private Ast.Expr notExpr() {
        if (accept(Token.Type.NOT)) return new Ast.UnaryOp("NOT", notExpr());
        return comparison();
    }

    private Ast.Expr comparison() {
        Ast.Expr left = additive();
        while (true) {
            Token t = peek();
            switch (t.type) {
                case EQ, NEQ, LT, LE, GT, GE -> {
                    next();
                    left = new Ast.BinOp(opText(t), left, additive());
                }
                case LIKE -> {
                    next();
                    left = new Ast.LikeOp(left, stringLiteral(), false);
                }
                case IN -> {
                    next();
                    left = inTail(left, false);
                }
                case BETWEEN -> {
                    next();
                    Ast.Expr low = additive();
                    expect(Token.Type.AND);
                    Ast.Expr high = additive();
                    left = new Ast.BetweenOp(left, low, high, false);
                }
                case IS -> {
                    next();
                    boolean negated = accept(Token.Type.NOT);
                    expect(Token.Type.NULL);
                    left = new Ast.IsNullOp(left, negated);
                }
                case NOT -> {
                    // NOT LIKE / NOT IN / NOT BETWEEN
                    next();
                    Token t2 = peek();
                    switch (t2.type) {
                        case LIKE -> {
                            next();
                            left = new Ast.LikeOp(left, stringLiteral(), true);
                        }
                        case IN -> {
                            next();
                            left = inTail(left, true);
                        }
                        case BETWEEN -> {
                            next();
                            Ast.Expr low = additive();
                            expect(Token.Type.AND);
                            Ast.Expr high = additive();
                            left = new Ast.BetweenOp(left, low, high, true);
                        }
                        default -> throw Token.err("期望 LIKE/IN/BETWEEN 跟在 NOT 后", t2.pos);
                    }
                }
                default -> {
                    return left;
                }
            }
        }
    }

    private Ast.Expr inTail(Ast.Expr operand, boolean negated) {
        expect(Token.Type.LPAREN);
        if (peek().is(Token.Type.SELECT)) {
            Ast.SelectStmt q = selectStatement();
            expect(Token.Type.RPAREN);
            return new Ast.InQueryOp(operand, q, negated);
        }
        List<Ast.Expr> items = new ArrayList<>();
        do { items.add(expr()); } while (accept(Token.Type.COMMA));
        expect(Token.Type.RPAREN);
        return new Ast.InListOp(operand, items, negated);
    }

    private static String opText(Token t) {
        return switch (t.type) {
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            default -> throw new IllegalStateException();
        };
    }

    private Ast.Expr additive() {
        Ast.Expr left = multiplicative();
        while (true) {
            if (accept(Token.Type.PLUS)) left = new Ast.BinOp("+", left, multiplicative());
            else if (accept(Token.Type.MINUS)) left = new Ast.BinOp("-", left, multiplicative());
            else return left;
        }
    }

    private Ast.Expr multiplicative() {
        Ast.Expr left = unary();
        while (true) {
            if (accept(Token.Type.STAR)) left = new Ast.BinOp("*", left, unary());
            else if (accept(Token.Type.SLASH)) left = new Ast.BinOp("/", left, unary());
            else if (accept(Token.Type.PERCENT)) left = new Ast.BinOp("%", left, unary());
            else return left;
        }
    }

    private Ast.Expr unary() {
        if (accept(Token.Type.MINUS)) return new Ast.UnaryOp("-", unary());
        return primary();
    }

    private Ast.Expr primary() {
        Token t = peek();
        switch (t.type) {
            case NUMBER -> {
                next();
                String s = t.text;
                if (s.contains(".")) return new Ast.Literal(Double.parseDouble(s));
                long v;
                try {
                    v = Long.parseLong(s);
                } catch (NumberFormatException e) {
                    throw Token.err("整数超出范围: " + s, t.pos);
                }
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                    return new Ast.Literal((int) v);
                return new Ast.Literal(v);
            }
            case STRING -> {
                next();
                return new Ast.Literal(t.text);
            }
            case NULL -> {
                next();
                return new Ast.Literal(null);
            }
            case MINUS -> {
                next();
                return new Ast.UnaryOp("-", unary());
            }
            case LPAREN -> {
                next();
                if (peek().is(Token.Type.SELECT)) {
                    Ast.SelectStmt q = selectStatement();
                    expect(Token.Type.RPAREN);
                    return new Ast.ScalarQueryOp(q);
                }
                Ast.Expr e = expr();
                expect(Token.Type.RPAREN);
                return e;
            }
            case EXISTS -> {
                next();
                expect(Token.Type.LPAREN);
                Ast.SelectStmt q = selectStatement();
                expect(Token.Type.RPAREN);
                return new Ast.ExistsOp(q, false);
            }
            case COUNT, SUM, AVG, MIN, MAX -> {
                next();
                boolean star = false;
                Ast.Expr arg = null;
                if (t.type == Token.Type.COUNT && accept(Token.Type.LPAREN)) {
                    if (accept(Token.Type.STAR)) star = true;
                    else arg = expr();
                    expect(Token.Type.RPAREN);
                    return new Ast.FuncCall("COUNT", arg, star);
                }
                expect(Token.Type.LPAREN);
                arg = expr();
                expect(Token.Type.RPAREN);
                return new Ast.FuncCall(t.type.name(), arg, false);
            }
            case IDENT -> {
                next();
                if (accept(Token.Type.DOT)) {
                    String col = identifier();
                    return new Ast.ColRef(t.text, col);
                }
                return new Ast.ColRef(null, t.text);
            }
            default -> throw Token.err("意外的词元 " + t.text, t.pos);
        }
    }

    // ---------- 工具 ----------

    private List<Ast.Expr> exprList() {
        List<Ast.Expr> out = new ArrayList<>();
        do { out.add(expr()); } while (accept(Token.Type.COMMA));
        return out;
    }

    private long longLiteral() {
        Token t = next();
        if (t.type == Token.Type.NUMBER) {
            try { return Long.parseLong(t.text); } catch (NumberFormatException e) { /* 小数 */ }
        }
        throw Token.err("期望整数，得到 " + t.text, t.pos);
    }

    private String stringLiteral() {
        Token t = next();
        if (t.type != Token.Type.STRING) throw Token.err("期望字符串字面量，得到 " + t.text, t.pos);
        return t.text;
    }

    private String identifier() {
        Token t = next();
        if (t.type != Token.Type.IDENT)
            throw Token.err("期望标识符，得到 " + t.text + (t.text.isEmpty() ? "(结束)" : ""), t.pos);
        return t.text;
    }

    private boolean accept(Token.Type type) {
        if (peek().is(type)) {
            next();
            return true;
        }
        return false;
    }

    private void expect(Token.Type type) {
        Token t = peek();
        if (!t.is(type))
            throw Token.err("期望 " + type + "，得到 " + (t.text.isEmpty() ? "<EOF>" : t.text), t.pos);
        next();
    }

    private Token peek() {
        return toks.get(i);
    }

    private Token next() {
        return toks.get(i++);
    }
}
