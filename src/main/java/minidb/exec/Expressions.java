package minidb.exec;

import minidb.common.MiniDbException;
import minidb.sql.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 表达式绑定与求值。计划构造时把 AST 绑定到 scope（输出列名列表）生成 EvalNode，
 * 运行期对 Object[] 行直接求值，避免每行重复做列名解析。
 */
public final class Expressions {
    private Expressions() {}

    public interface EvalNode {
        Object eval(Object[] row);
    }

    /** 子查询与外层作用域的绑定钩子，由 Executor 实现。 */
    public interface Binder {
        /** InQueryOp / ExistsOp / ScalarQueryOp */
        EvalNode bindSubquery(Ast.Expr e, List<String> scope);

        /** 内层 scope 解析不到的列 → 到外层查询找（相关子查询）；找不到返回 null。 */
        EvalNode lookupOuter(String table, String column);
    }

    /** 绑定表达式到 scope（列名形如 "t.col" 或 "col"）。 */
    public static EvalNode bind(Ast.Expr e, List<String> scope) {
        return bind(e, scope, null);
    }

    public static EvalNode bind(Ast.Expr e, List<String> scope, Binder binder) {
        // 常量折叠：纯常量子树（无列引用/子查询）在绑定期求值一次
        if ((e instanceof Ast.BinOp || e instanceof Ast.UnaryOp) && isConstantExpr(e)) {
            Object v = bindNoFold(e, List.of(), binder).eval(new Object[0]);
            return row -> v;
        }
        return bindNoFold(e, scope, binder);
    }

    private static EvalNode bindNoFold(Ast.Expr e, List<String> scope, Binder binder) {
        if (e instanceof Ast.Literal lit) {
            Object v = lit.value();
            return row -> v;
        }
        if (e instanceof Ast.ColRef ref) return bindCol(ref, scope, binder);
        if (e instanceof Ast.BinOp b) {
            String op = b.op();
            EvalNode l = bind(b.left(), scope, binder);
            EvalNode r = bind(b.right(), scope, binder);
            return switch (op) {
                case "AND" -> row -> truthyAnd(l.eval(row), r.eval(row));
                case "OR" -> row -> truthyOr(l.eval(row), r.eval(row));
                case "=", "!=", "<", "<=", ">", ">=" -> row ->
                        compareOp(op, l.eval(row), r.eval(row));
                case "+", "-", "*", "/", "%" -> row ->
                        arith(op, l.eval(row), r.eval(row));
                default -> throw new MiniDbException(MiniDbException.Code.EXEC, "未知运算符 " + op);
            };
        }
        if (e instanceof Ast.UnaryOp u) {
            EvalNode x = bind(u.operand(), scope, binder);
            if (u.op().equals("NOT")) return row -> truthy(x.eval(row)) ? Boolean.FALSE : Boolean.TRUE;
            return row -> negate(x.eval(row));
        }
        if (e instanceof Ast.LikeOp like) {
            EvalNode x = bind(like.operand(), scope, binder);
            Pattern p = likeRegex(like.pattern());
            boolean neg = like.negated();
            return row -> {
                Object v = x.eval(row);
                if (v == null) return Boolean.FALSE;
                boolean m = p.matcher(v.toString()).matches();
                return neg ? !m : m;
            };
        }
        if (e instanceof Ast.BetweenOp b) {
            EvalNode x = bind(b.operand(), scope, binder);
            EvalNode lo = bind(b.low(), scope, binder);
            EvalNode hi = bind(b.high(), scope, binder);
            boolean neg = b.negated();
            return row -> {
                Object v = x.eval(row);
                Boolean ge = compareOp(">=", v, lo.eval(row));
                Boolean le = compareOp("<=", v, hi.eval(row));
                boolean in = Boolean.TRUE.equals(ge) && Boolean.TRUE.equals(le);
                return neg ? !in : in;
            };
        }
        if (e instanceof Ast.IsNullOp isn) {
            EvalNode x = bind(isn.operand(), scope, binder);
            boolean neg = isn.negated();
            return row -> {
                boolean isNull = x.eval(row) == null;
                return neg ? !isNull : isNull;
            };
        }
        if (e instanceof Ast.InListOp in) {
            EvalNode x = bind(in.operand(), scope, binder);
            List<EvalNode> items = in.items().stream().map(it -> bind(it, scope, binder)).toList();
            boolean neg = in.negated();
            return row -> {
                Object v = x.eval(row);
                if (v == null) return Boolean.FALSE;
                boolean found = false;
                for (EvalNode it : items) {
                    if (Boolean.TRUE.equals(compareOp("=", v, it.eval(row)))) {
                        found = true;
                        break;
                    }
                }
                return neg ? !found : found;
            };
        }
        if (binder != null && (e instanceof Ast.InQueryOp || e instanceof Ast.ExistsOp
                || e instanceof Ast.ScalarQueryOp)) {
            return binder.bindSubquery(e, scope);
        }
        throw new MiniDbException(MiniDbException.Code.EXEC,
                "该表达式不能在此绑定: " + e.getClass().getSimpleName());
    }

    /** 子树是否为纯常量（仅 Literal/BinOp/UnaryOp 组成，无列引用与子查询）。 */
    private static boolean isConstantExpr(Ast.Expr e) {
        if (e instanceof Ast.Literal) return true;
        if (e instanceof Ast.UnaryOp u) return isConstantExpr(u.operand());
        if (e instanceof Ast.BinOp b) return isConstantExpr(b.left()) && isConstantExpr(b.right());
        return false;
    }

    private static EvalNode bindCol(Ast.ColRef ref, List<String> scope, Binder binder) {
        final int idx;
        if (ref.table() != null) {
            String want = ref.table() + "." + ref.column();
            int k = scope.indexOf(want);
            if (k < 0) k = indexOfIgnoreCase(scope, want);
            if (k < 0 && binder != null) {
                EvalNode outer = binder.lookupOuter(ref.table(), ref.column());
                if (outer != null) return outer;
            }
            if (k < 0)
                throw new MiniDbException(MiniDbException.Code.EXEC, "未知列 " + want);
            idx = k;
        } else {
            int hit = -1;
            for (int i = 0; i < scope.size(); i++) {
                String col = scope.get(i);
                int dot = col.lastIndexOf('.');
                String bare = dot >= 0 ? col.substring(dot + 1) : col;
                if (bare.equalsIgnoreCase(ref.column())) {
                    if (hit >= 0)
                        throw new MiniDbException(MiniDbException.Code.EXEC,
                                "列名有歧义: " + ref.column());
                    hit = i;
                }
            }
            if (hit < 0 && binder != null) {
                EvalNode outer = binder.lookupOuter(ref.table(), ref.column());
                if (outer != null) return outer;
            }
            if (hit < 0)
                throw new MiniDbException(MiniDbException.Code.EXEC, "未知列 " + ref.column());
            idx = hit;
        }
        return row -> row[idx];
    }

    private static int indexOfIgnoreCase(List<String> scope, String want) {
        for (int i = 0; i < scope.size(); i++)
            if (scope.get(i).equalsIgnoreCase(want)) return i;
        return -1;
    }

    // ---------- 运行期语义 ----------

    public static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static boolean truthyAnd(Object a, Object b) {
        return truthy(a) && truthy(b);
    }

    private static boolean truthyOr(Object a, Object b) {
        return truthy(a) || truthy(b);
    }

    /** NULL 参与比较一律 false（NULL 不与任何值相等） */
    public static Boolean compareOp(String op, Object a, Object b) {
        if (a == null || b == null) return Boolean.FALSE;
        int c = compare(a, b);
        return switch (op) {
            case "=" -> c == 0;
            case "!=" -> c != 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> throw new MiniDbException(MiniDbException.Code.EXEC, "未知比较 " + op);
        };
    }

    /** 数值跨类型比较，字符串按字典序 */
    public static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || b instanceof Double) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Boolean ba && b instanceof Boolean bb) return ba.compareTo(bb);
        throw new MiniDbException(MiniDbException.Code.EXEC,
                "不可比较的类型: " + typeName(a) + " vs " + typeName(b));
    }

    private static String typeName(Object v) {
        return v == null ? "NULL" : v.getClass().getSimpleName();
    }

    /** NULL 参与算术结果为 NULL */
    private static Object arith(String op, Object a, Object b) {
        if (a == null || b == null) return null;
        if (!(a instanceof Number na) || !(b instanceof Number nb))
            throw new MiniDbException(MiniDbException.Code.EXEC,
                    "算术运算需要数值: " + typeName(a) + " " + op + " " + typeName(b));
        boolean dbl = a instanceof Double || b instanceof Double;
        if (dbl) {
            double x = na.doubleValue(), y = nb.doubleValue();
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? null : x / y; // 除零 → NULL（简化语义）
                case "%" -> y == 0 ? null : x % y;
                default -> throw new IllegalStateException();
            };
        }
        long x = na.longValue(), y = nb.longValue();
        long r = switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> y == 0 ? 0 : x / y;
            case "%" -> y == 0 ? 0 : x % y;
            default -> throw new IllegalStateException();
        };
        if (op.equals("/") && y == 0) return null;
        if (op.equals("%") && y == 0) return null;
        if (r >= Integer.MIN_VALUE && r <= Integer.MAX_VALUE && !(a instanceof Long) && !(b instanceof Long))
            return (int) r;
        return r;
    }

    private static Object negate(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return -i;
        if (v instanceof Long l) return -l;
        if (v instanceof Double d) return -d;
        throw new MiniDbException(MiniDbException.Code.EXEC, "取负需要数值: " + typeName(v));
    }

    /** LIKE 模式 → 正则：% → .*，_ → .，其余转义 */
    static Pattern likeRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '%' -> sb.append(".*");
                case '_' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        try {
            return Pattern.compile(sb.toString(), Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new MiniDbException(MiniDbException.Code.EXEC, "非法 LIKE 模式: " + pattern, e);
        }
    }

    /** AND 树展平为合取列表 */
    public static List<Ast.Expr> flattenAnd(Ast.Expr e) {
        List<Ast.Expr> out = new ArrayList<>();
        flattenAndRec(e, out);
        return out;
    }

    private static void flattenAndRec(Ast.Expr e, List<Ast.Expr> out) {
        if (e instanceof Ast.BinOp b && b.op().equals("AND")) {
            flattenAndRec(b.left(), out);
            flattenAndRec(b.right(), out);
        } else {
            out.add(e);
        }
    }

    public static Ast.Expr andAll(List<Ast.Expr> list) {
        if (list.isEmpty()) return null;
        Ast.Expr acc = list.get(0);
        for (int i = 1; i < list.size(); i++) acc = new Ast.BinOp("AND", acc, list.get(i));
        return acc;
    }

    /** 收集表达式中引用的列（含未限定名） */
    public static void collectColumns(Ast.Expr e, List<Ast.ColRef> out) {
        if (e == null) return;
        if (e instanceof Ast.ColRef c) {
            out.add(c);
        } else if (e instanceof Ast.BinOp b) {
            collectColumns(b.left(), out);
            collectColumns(b.right(), out);
        } else if (e instanceof Ast.UnaryOp u) {
            collectColumns(u.operand(), out);
        } else if (e instanceof Ast.LikeOp l) {
            collectColumns(l.operand(), out);
        } else if (e instanceof Ast.BetweenOp b) {
            collectColumns(b.operand(), out);
            collectColumns(b.low(), out);
            collectColumns(b.high(), out);
        } else if (e instanceof Ast.IsNullOp i) {
            collectColumns(i.operand(), out);
        } else if (e instanceof Ast.InListOp in) {
            collectColumns(in.operand(), out);
            for (Ast.Expr it : in.items()) collectColumns(it, out);
        } else if (e instanceof Ast.FuncCall f) {
            collectColumns(f.arg(), out);
        }
        // 子查询表达式（InQueryOp/ExistsOp/ScalarQueryOp）不外提
    }
}
