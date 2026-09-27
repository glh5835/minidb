package minidb.repl;

import minidb.common.MiniDbException;
import minidb.exec.Ops;
import minidb.exec.SeqScanOp;
import minidb.storage.Column;
import minidb.storage.ColumnType;
import minidb.storage.Database;
import minidb.storage.Row;
import minidb.storage.Table;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段1 REPL：读一行执行一行。
 * 按任务书要求“手工构造查询计划，不要 SQL 解析”——
 * .scan 等命令直接拼装火山算子（如 SeqScanOp）执行。
 */
public final class Repl implements AutoCloseable {
    private final BufferedReader in;
    private final PrintStream out;
    private Database db;
    private Path dbFile;
    private minidb.exec.Executor executor;

    public Repl(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    public void setDatabase(Database db) {
        this.db = db;
        this.dbFile = null;
        this.executor = db != null ? new minidb.exec.Executor(db) : null;
    }

    public void run() throws IOException {
        banner();
        String line;
        while ((line = in.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!execute(trimmed)) break;
        }
    }

    /** 执行一条命令或 SQL；返回 false 表示退出。 */
    public boolean execute(String line) {
        if (!line.startsWith(".")) {
            // 阶段3：直接当 SQL 执行
            try {
                if (executor == null)
                    throw new MiniDbException(MiniDbException.Code.CATALOG, "未打开数据库，先 .open <file>");
                var result = executor.execute(line);
                if (result.message() != null) out.println(result.message());
                if (!result.columns().isEmpty()) {
                    out.println(String.join(" | ", result.columns()));
                    for (Object[] row : result.rows()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < row.length; i++) {
                            if (i > 0) sb.append(" | ");
                            sb.append(row[i] == null ? "NULL" : row[i]);
                        }
                        out.println(sb);
                    }
                    out.println("(" + result.rowCount() + " rows)");
                }
            } catch (MiniDbException e) {
                out.println("ERROR: " + e.getMessage());
            } catch (Exception e) {
                out.println("ERROR: " + e);
            }
            return true;
        }
        String[] tok = split(line);
        String cmd = tok[0].toLowerCase();
        try {
            switch (cmd) {
                case ".quit", ".exit" -> {
                    closeDb();
                    out.println("bye");
                    return false;
                }
                case ".help" -> help();
                case ".open" -> cmdOpen(tok);
                case ".close" -> closeDb();
                case ".tables" -> cmdTables();
                case ".create" -> cmdCreate(tok);
                case ".drop" -> cmdDrop(tok);
                case ".insert" -> cmdInsert(tok);
                case ".scan" -> cmdScan(tok);
                case ".get" -> cmdGet(tok);
                case ".delete" -> cmdDelete(tok);
                case ".update" -> cmdUpdate(tok);
                case ".count" -> cmdCount(tok);
                case ".pages" -> cmdPages(tok);
                case ".stats" -> cmdStats();
                case ".flush" -> requireDb().flush();
                default -> out.println("未知命令: " + cmd + "（输入 .help 查看帮助；其余输入按 SQL 执行）");
            }
        } catch (MiniDbException e) {
            out.println("ERROR: " + e.getMessage());
        } catch (Exception e) {
            out.println("ERROR: " + e);
        }
        return true;
    }

    private void banner() {
        out.println("MiniDB 阶段1 REPL —— 手工查询计划版（.help 查看命令）");
    }

    private void help() {
        out.println("""
                .open <file>                       打开/创建数据库文件
                .close                             关闭当前数据库
                .tables                            列出所有表
                .create <t> <col:type[ (n)],...>   建表，如 create t id:int,name:varchar(20)
                .drop <t>                          删表
                .insert <t> <v>,...                插入一行，字符串用单引号
                .scan <t>                          全表扫描（手工构造 SeqScan 计划）
                .get <t> <page> <slot>             按 RID 读一行
                .delete <t> <page> <slot>          按 RID 删一行
                .update <t> <page> <slot> <v>,...  按 RID 改一行
                .count <t>                         行数
                .pages <t>                         页数/空闲空间统计
                .stats                             缓冲池命中统计
                .flush                             刷盘
                .quit                              退出""");
    }

    private Database requireDb() {
        if (db == null)
            throw new MiniDbException(MiniDbException.Code.CATALOG, "未打开数据库，先 .open <file>");
        return db;
    }

    private Table requireTable(String name) {
        if (!requireDb().hasTable(name))
            throw new MiniDbException(MiniDbException.Code.CATALOG, "表不存在: " + name);
        return db.getTable(name);
    }

    private void closeDb() {
        if (db != null) {
            db.close();
            db = null;
            executor = null;
            out.println("数据库已关闭");
        }
    }

    private void cmdOpen(String[] tok) {
        if (tok.length < 2) throw new MiniDbException(MiniDbException.Code.CATALOG, "用法: .open <file>");
        closeDb();
        dbFile = Path.of(tok[1]);
        db = Database.open(dbFile);
        executor = new minidb.exec.Executor(db);
        out.println("已打开 " + dbFile);
    }

    private void cmdTables() {
        List<String> names = requireDb().tableNames();
        out.println(names.isEmpty() ? "(无表)" : String.join(", ", names));
    }

    /** create t id:int,name:varchar(20) */
    private void cmdCreate(String[] tok) {
        if (tok.length < 3)
            throw new MiniDbException(MiniDbException.Code.SCHEMA, "用法: .create <t> <col:type,...>");
        List<Column> cols = new ArrayList<>();
        for (String part : joinFrom(tok, 2).split(",", 0)) {
            String c = part.trim();
            int colon = c.indexOf(':');
            if (colon < 0)
                throw new MiniDbException(MiniDbException.Code.SCHEMA, "列定义缺少类型: " + c);
            String name = c.substring(0, colon).trim();
            String typeStr = c.substring(colon + 1).trim();
            ColumnType type;
            int maxLen = 0;
            int paren = typeStr.indexOf('(');
            if (paren >= 0) {
                if (!typeStr.endsWith(")"))
                    throw new MiniDbException(MiniDbException.Code.SCHEMA, "坏的类型定义: " + typeStr);
                maxLen = Integer.parseInt(typeStr.substring(paren + 1, typeStr.length() - 1).trim());
                type = ColumnType.of(typeStr.substring(0, paren).trim());
            } else {
                type = ColumnType.of(typeStr);
            }
            cols.add(type == ColumnType.VARCHAR ? new Column(name, type, maxLen)
                    : Column.fixed(name, type));
        }
        requireDb().createTable(tok[1], cols);
        out.println("已创建表 " + tok[1]);
    }

    private void cmdDrop(String[] tok) {
        if (tok.length < 2) throw new MiniDbException(MiniDbException.Code.CATALOG, "用法: .drop <t>");
        requireDb().dropTable(tok[1]);
        out.println("已删除表 " + tok[1]);
    }

    private void cmdInsert(String[] tok) {
        if (tok.length < 3)
            throw new MiniDbException(MiniDbException.Code.RECORD, "用法: .insert <t> <v>,...");
        Table t = requireTable(tok[1]);
        Object[] values = parseValues(t, joinFrom(tok, 2));
        var rid = t.insert(values);
        out.println("inserted " + rid);
    }

    private void cmdScan(String[] tok) {
        if (tok.length < 2) throw new MiniDbException(MiniDbException.Code.EXEC, "用法: .scan <t>");
        Table t = requireTable(tok[1]);
        // 手工构造查询计划：SeqScan
        int n = Ops.dump(new SeqScanOp(t), out);
        out.println("(" + n + " rows)");
    }

    private void cmdGet(String[] tok) {
        if (tok.length < 4)
            throw new MiniDbException(MiniDbException.Code.RECORD, "用法: .get <t> <page> <slot>");
        Table t = requireTable(tok[1]);
        var rid = new minidb.common.Rid(Integer.parseInt(tok[2]), Integer.parseInt(tok[3]));
        out.println(Ops.formatRow(new Row(rid, t.get(rid))));
    }

    private void cmdDelete(String[] tok) {
        if (tok.length < 4)
            throw new MiniDbException(MiniDbException.Code.RECORD, "用法: .delete <t> <page> <slot>");
        Table t = requireTable(tok[1]);
        t.delete(new minidb.common.Rid(Integer.parseInt(tok[2]), Integer.parseInt(tok[3])));
        out.println("deleted");
    }

    private void cmdUpdate(String[] tok) {
        if (tok.length < 5)
            throw new MiniDbException(MiniDbException.Code.RECORD, "用法: .update <t> <page> <slot> <v>,...");
        Table t = requireTable(tok[1]);
        var rid = new minidb.common.Rid(Integer.parseInt(tok[2]), Integer.parseInt(tok[3]));
        Object[] values = parseValues(t, joinFrom(tok, 4));
        var newRid = t.update(rid, values);
        out.println("updated -> " + newRid);
    }

    private void cmdCount(String[] tok) {
        if (tok.length < 2) throw new MiniDbException(MiniDbException.Code.EXEC, "用法: .count <t>");
        out.println(requireTable(tok[1]).rowCount());
    }

    private void cmdPages(String[] tok) {
        if (tok.length < 2) throw new MiniDbException(MiniDbException.Code.EXEC, "用法: .pages <t>");
        Table t = requireTable(tok[1]);
        out.println("pages=" + t.pageCount() + " rows=" + t.rowCount()
                + " freeBytes=" + t.totalFreeBytes());
    }

    private void cmdStats() {
        var eng = requireDb().engine();
        var pool = eng.pool();
        out.printf("bufferPool: size=%d/%d hits=%d misses=%d evictions=%d writebacks=%d " +
                        "diskPages=%d bitmaps=%d%n",
                pool.size(), pool.capacity(), pool.hits(), pool.misses(),
                pool.evictions(), pool.writebacks(), eng.pageCount(), eng.bitmapCount());
    }

    // ---- 解析辅助 ----

    private static String[] split(String line) {
        return line.trim().split("\\s+", 2).length > 0 ? line.trim().split("\\s+") : new String[]{""};
    }

    private static String joinFrom(String[] tok, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tok.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(tok[i]);
        }
        return sb.toString();
    }

    /** 按列类型解析 "1, 'alice', 2.5" */
    static Object[] parseValues(Table t, String csv) {
        List<String> raw = splitCsv(csv);
        List<Column> cols = t.schema().columns();
        if (raw.size() != cols.size())
            throw new MiniDbException(MiniDbException.Code.RECORD,
                    "期望 " + cols.size() + " 个值，得到 " + raw.size());
        Object[] out = new Object[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            String s = raw.get(i).trim();
            out[i] = switch (cols.get(i).type()) {
                case INT -> Integer.parseInt(s);
                case BIGINT -> Long.parseLong(s);
                case DOUBLE -> Double.parseDouble(s);
                case VARCHAR -> {
                    if (s.length() < 2 || !s.startsWith("'") || !s.endsWith("'"))
                        throw new MiniDbException(MiniDbException.Code.RECORD,
                                "VARCHAR 值需要单引号: " + s);
                    yield s.substring(1, s.length() - 1);
                }
            };
        }
        return out;
    }

    /** 逗号切分，忽略单引号内的逗号 */
    static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (c == ',' && !inQuote) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    @Override
    public void close() {
        closeDb();
    }

    public static void main(String[] args) throws Exception {
        try (Repl repl = new Repl(
                new BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out)) {
            if (args.length > 0) {
                repl.execute(".open " + args[0]);
            }
            repl.run();
        }
    }
}
