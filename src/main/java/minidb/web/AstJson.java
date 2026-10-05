package minidb.web;

import minidb.sql.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AST → JSON（SQL 执行流程实验用）。真实解析产物，不做任何语义加工。 */
public final class AstJson {
    private AstJson() {}

    public static Map<String, Object> any(Object stmt) {
        if (stmt instanceof Ast.SelectStmt s) return select(s);
        if (stmt instanceof Ast.InsertStmt i) return insert(i);
        if (stmt instanceof Ast.UpdateStmt u) return update(u);
        if (stmt instanceof Ast.DeleteStmt d) return delete(d);
        if (stmt instanceof Ast.CreateTableStmt c) {
            Map<String, Object> m = base("CreateTable", c.table());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Ast.ColumnDef cd : c.columns())
                cols.add(Map.of("name", cd.name(), "type", cd.type(),
                        "size", cd.size() == null ? "NULL" : String.valueOf(cd.size())));
            m.put("columns", cols);
            return m;
        }
        if (stmt instanceof Ast.DropTableStmt d) return base("DropTable", d.table());
        if (stmt instanceof Ast.CreateIndexStmt c) {
            Map<String, Object> m = base("CreateIndex", c.index());
            m.put("table", c.table());
            m.put("column", c.column());
            return m;
        }
        if (stmt instanceof Ast.DropIndexStmt d) return base("DropIndex", d.index());
        if (stmt instanceof minidb.sql.Token.Type t) return Map.of("node", "TxnStatement", "statement", t.name());
        return Map.of("node", stmt.getClass().getSimpleName());
    }

    private static Map<String, Object> base(String node, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", node);
        m.put("target", target);
        return m;
    }

    public static Map<String, Object> select(Ast.SelectStmt s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", "SelectStmt");
        m.put("distinct", s.distinct());
        List<Object> items = new ArrayList<>();
        for (Ast.SelectItem item : s.items()) {
            if (item.star()) items.add(Map.of("star", true));
            else {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("expr", expr(item.expr()));
                if (item.alias() != null) im.put("alias", item.alias());
                items.add(im);
            }
        }
        m.put("items", items);
        if (s.from() != null) m.put("from", tableRef(s.from()));
        if (s.where() != null) m.put("where", expr(s.where()));
        if (s.groupBy() != null) {
            List<Object> g = new ArrayList<>();
            for (Ast.Expr e : s.groupBy()) g.add(expr(e));
            m.put("groupBy", g);
        }
        if (s.having() != null) m.put("having", expr(s.having()));
        if (s.orderBy() != null) {
            List<Object> o = new ArrayList<>();
            for (Ast.OrderItem oi : s.orderBy())
                o.add(Map.of("expr", expr(oi.expr()), "desc", oi.desc()));
            m.put("orderBy", o);
        }
        if (s.limit() != null) m.put("limit", String.valueOf(s.limit()));
        if (s.offset() != null) m.put("offset", String.valueOf(s.offset()));
        return m;
    }

    public static Map<String, Object> tableRef(Ast.TableRef ref) {
        if (ref instanceof Ast.NamedTable nt) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", "NamedTable");
            m.put("table", nt.name());
            if (nt.alias() != null) m.put("alias", nt.alias());
            return m;
        }
        Ast.Join j = (Ast.Join) ref;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", "Join");
        m.put("type", j.type());
        m.put("left", tableRef(j.left()));
        m.put("right", tableRef(j.right()));
        if (j.on() != null) m.put("on", expr(j.on()));
        return m;
    }

    public static Map<String, Object> expr(Ast.Expr e) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (e instanceof Ast.Literal l) {
            m.put("node", "Literal");
            m.put("value", l.value() == null ? "NULL" : Json.toJsonValue(l.value()));
            m.put("valueType", l.value() == null ? "NULL" : l.value().getClass().getSimpleName());
        } else if (e instanceof Ast.ColRef c) {
            m.put("node", "ColRef");
            if (c.table() != null) m.put("table", c.table());
            m.put("column", c.column());
        } else if (e instanceof Ast.BinOp b) {
            m.put("node", "BinOp");
            m.put("op", b.op());
            m.put("left", expr(b.left()));
            m.put("right", expr(b.right()));
        } else if (e instanceof Ast.UnaryOp u) {
            m.put("node", "UnaryOp");
            m.put("op", u.op());
            m.put("operand", expr(u.operand()));
        } else if (e instanceof Ast.FuncCall f) {
            m.put("node", "FuncCall");
            m.put("name", f.name());
            m.put("star", f.star());
            if (!f.star() && f.arg() != null) m.put("arg", expr(f.arg()));
        } else if (e instanceof Ast.LikeOp l) {
            m.put("node", "LikeOp");
            m.put("negated", l.negated());
            m.put("operand", expr(l.operand()));
            m.put("pattern", l.pattern());
        } else if (e instanceof Ast.BetweenOp b) {
            m.put("node", "BetweenOp");
            m.put("negated", b.negated());
            m.put("operand", expr(b.operand()));
            m.put("low", expr(b.low()));
            m.put("high", expr(b.high()));
        } else if (e instanceof Ast.IsNullOp i) {
            m.put("node", "IsNullOp");
            m.put("negated", i.negated());
            m.put("operand", expr(i.operand()));
        } else if (e instanceof Ast.InListOp in) {
            m.put("node", "InListOp");
            m.put("negated", in.negated());
            m.put("operand", expr(in.operand()));
            List<Object> items = new ArrayList<>();
            for (Ast.Expr it : in.items()) items.add(expr(it));
            m.put("items", items);
        } else if (e instanceof Ast.InQueryOp q) {
            m.put("node", "InQueryOp");
            m.put("negated", q.negated());
            m.put("operand", expr(q.operand()));
            m.put("query", select(q.query()));
        } else if (e instanceof Ast.ExistsOp ex) {
            m.put("node", "ExistsOp");
            m.put("negated", ex.negated());
            m.put("query", select(ex.query()));
        } else if (e instanceof Ast.ScalarQueryOp sq) {
            m.put("node", "ScalarQueryOp");
            m.put("query", select(sq.query()));
        } else {
            m.put("node", e.getClass().getSimpleName());
        }
        return m;
    }

    private static Map<String, Object> insert(Ast.InsertStmt i) {
        Map<String, Object> m = base("InsertStmt", i.table());
        if (i.columns() != null) m.put("columns", i.columns());
        List<Object> rows = new ArrayList<>();
        for (List<Ast.Expr> row : i.rows()) {
            List<Object> vals = new ArrayList<>();
            for (Ast.Expr e : row) vals.add(expr(e));
            rows.add(vals);
        }
        m.put("rows", rows);
        return m;
    }

    private static Map<String, Object> update(Ast.UpdateStmt u) {
        Map<String, Object> m = base("UpdateStmt", u.table());
        List<Object> sets = new ArrayList<>();
        for (Ast.UpdateStmt.Assign a : u.sets())
            sets.add(Map.of("column", a.column(), "value", expr(a.value())));
        m.put("sets", sets);
        if (u.where() != null) m.put("where", expr(u.where()));
        return m;
    }

    private static Map<String, Object> delete(Ast.DeleteStmt d) {
        Map<String, Object> m = base("DeleteStmt", d.table());
        if (d.where() != null) m.put("where", expr(d.where()));
        return m;
    }
}
