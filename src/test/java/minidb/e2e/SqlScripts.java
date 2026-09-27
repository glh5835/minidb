package minidb.e2e;

import minidb.common.MiniDbException;
import minidb.exec.Executor;
import minidb.sql.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 脚本端到端测试框架（由 SqlE2eTest 驱动）。
 *
 * 脚本格式（.sql）：
 * <pre>
 * -- @case 用例名
 * CREATE TABLE t (a INT);
 * INSERT INTO t VALUES (1);
 * -- expect: 1
 * -- expect-error
 * INSERT ...
 * </pre>
 * 一个文件一个数据库（文件内状态延续）。每条 case：若干语句 + 若干 expect 行。
 * expect 行 = case 内最后一条 SELECT 输出的一行文本（" | " 连接，NULL 字面量，DOUBLE 整数带 .0）。
 * `-- expect-error` 修饰其后的第一条语句：该语句必须执行失败。
 */
final class SqlScripts {
    private SqlScripts() {}

    record Case(String file, String name, List<Object> statements, List<Boolean> errFlags,
                List<String> expects) {}

    static final class ScriptException extends RuntimeException {
        ScriptException(String msg) {
            super(msg);
        }
    }

    static List<Case> parse(String fileName, List<String> lines) {
        List<Case> cases = new ArrayList<>();
        String name = null;
        List<Object> statements = new ArrayList<>();
        List<Boolean> flags = new ArrayList<>();
        List<String> expects = new ArrayList<>();

        StringBuilder cur = new StringBuilder();
        boolean curErr = false;
        boolean inQuote = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("-- @case")) {
                curErr = flushStmt(cur, curErr, statements, flags);
                if (name != null && !statements.isEmpty()) {
                    cases.add(new Case(fileName, name, new ArrayList<>(statements),
                            new ArrayList<>(flags), new ArrayList<>(expects)));
                }
                name = line.substring("-- @case".length()).trim();
                statements.clear();
                flags.clear();
                expects.clear();
                continue;
            }
            if (line.startsWith("-- expect-error")) {
                curErr = flushStmt(cur, curErr, statements, flags) || true;
                continue;
            }
            if (line.startsWith("-- expect:")) {
                curErr = flushStmt(cur, curErr, statements, flags);
                expects.add(line.substring("-- expect:".length()).trim());
                continue;
            }
            if (line.startsWith("--")) continue;
            if (name == null) continue;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'') {
                    inQuote = !inQuote;
                    cur.append(c);
                } else if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-' && !inQuote) {
                    break; // 行尾注释
                } else if (c == ';' && !inQuote) {
                    curErr = flushStmt(cur, curErr, statements, flags);
                } else {
                    cur.append(c);
                }
            }
            cur.append(' ');
        }
        curErr = flushStmt(cur, curErr, statements, flags);
        if (name != null && !statements.isEmpty()) {
            cases.add(new Case(fileName, name, statements, flags, expects));
        }
        return cases;
    }

    /** 把当前缓冲解析成一条语句；返回重置后的 err 标志（false）。 */
    private static boolean flushStmt(StringBuilder cur, boolean err, List<Object> statements,
                                     List<Boolean> flags) {
        String sql = cur.toString().trim();
        cur.setLength(0);
        if (sql.isEmpty()) return err; // 空缓冲：保留 err 给下一条语句
        try {
            statements.add(Parser.parse(sql));
        } catch (MiniDbException e) {
            throw new ScriptException("解析失败: " + sql + " → " + e.getMessage());
        }
        flags.add(err);
        return false;
    }

    /** 执行一个文件的全部 case，返回错误列表（空 = 全部通过）。 */
    static List<String> run(List<Case> cases, Executor ex) {
        List<String> errors = new ArrayList<>();
        for (Case c : cases) {
            List<String> got = new ArrayList<>();
            boolean failed = false;
            for (int i = 0; i < c.statements().size(); i++) {
                Object stmt = c.statements().get(i);
                boolean expectErr = c.errFlags().get(i);
                try {
                    Executor.Result r = ex.execute(stmt);
                    for (Object[] row : r.rows()) got.add(formatRow(row));
                } catch (MiniDbException e) {
                    if (!expectErr) {
                        errors.add(c.file() + " / " + c.name() + ": 语句 " + (i + 1)
                                + " 意外失败: " + e.getMessage());
                        failed = true;
                        break;
                    }
                }
            }
            if (failed) continue;
            int n = c.expects().size();
            if (n > 0) {
                if (got.size() < n) {
                    errors.add(c.file() + " / " + c.name() + ": 期望 " + n + " 行输出，实际 " + got.size());
                } else {
                    List<String> tail = got.subList(got.size() - n, got.size());
                    for (int i = 0; i < n; i++) {
                        if (!tail.get(i).equals(c.expects().get(i))) {
                            errors.add(c.file() + " / " + c.name() + ": 第" + (i + 1)
                                    + "行期望 [" + c.expects().get(i) + "] 实际 [" + tail.get(i) + "]");
                        }
                    }
                }
            }
        }
        return errors;
    }

    static String formatRow(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(" | ");
            Object v = row[i];
            if (v instanceof Double d) {
                if (!d.isNaN() && !d.isInfinite() && d == Math.rint(d) && Math.abs(d) < 1e15) {
                    sb.append((long) (double) d).append(".0");
                } else {
                    sb.append(d);
                }
            } else {
                sb.append(v == null ? "NULL" : v);
            }
        }
        return sb.toString();
    }
}
