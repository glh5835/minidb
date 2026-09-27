package minidb.repl;

import minidb.storage.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** REPL：读一行执行一行（手工构造查询计划），含输出断言与故障注入 */
class ReplTest {
    @TempDir
    Path dir;

    private record Out(String text, Repl repl) {
        List<String> lines() {
            return text.lines().toList();
        }
    }

    private Out run(String... commands) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8);
        try (Repl repl = new Repl(new BufferedReader(new StringReader("")), ps)) {
            for (String c : commands) repl.execute(c);
        }
        return new Out(bos.toString(StandardCharsets.UTF_8), null);
    }

    private Out runScript(String script) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8);
        try (Repl repl = new Repl(new BufferedReader(new StringReader(script)), ps)) {
            repl.run();
        } catch (Exception ignored) {
        }
        return new Out(bos.toString(StandardCharsets.UTF_8), null);
    }

    private String dbPath(String n) {
        return dir.resolve(n).toString().replace('\\', '/');
    }

    @Test
    void createInsertScanFlow() {
        Out o = run(".open " + dbPath("a.db"),
                ".create t id:int,name:varchar(20)",
                ".insert t 1, 'alice'",
                ".scan t");
        assertTrue(o.text().contains("inserted"));
        assertTrue(o.text().contains("1, alice"));
        assertTrue(o.text().contains("(1 rows)"));
    }

    @Test
    void unknownCommandReported() {
        Out o = run(".frobnicate");
        assertTrue(o.text().contains("未知命令"));
    }

    @Test
    void errorWithoutOpenDatabase() {
        Out o = run(".create t id:int");
        assertTrue(o.text().contains("ERROR"));
        assertTrue(o.text().contains("未打开数据库"));
    }

    @Test
    void missingTableReported() {
        Out o = run(".open " + dbPath("b.db"), ".scan nope");
        assertTrue(o.text().contains("ERROR"));
        assertTrue(o.text().contains("表不存在"));
    }

    @Test
    void deleteAndGetByRid() {
        Out o = run(".open " + dbPath("c.db"),
                ".create t id:int",
                ".insert t 7",
                ".get t 3 0",
                ".delete t 3 0",
                ".count t");
        assertTrue(o.text().contains("7"));
        assertTrue(o.text().contains("deleted"));
        assertTrue(o.lines().contains("0"));
    }

    @Test
    void updateCommand() {
        Out o = run(".open " + dbPath("d.db"),
                ".create t id:int,name:varchar(10)",
                ".insert t 1, 'a'",
                ".update t 3 0 2, 'b'",
                ".scan t");
        assertTrue(o.text().contains("updated ->"));
        assertTrue(o.text().contains("2, b"));
    }

    @Test
    void countAndPages() {
        Out o = run(".open " + dbPath("e.db"),
                ".create t id:int",
                ".insert t 1", ".insert t 2", ".insert t 3",
                ".count t", ".pages t");
        assertTrue(o.lines().contains("3"));
        assertTrue(o.text().contains("pages=1 rows=3"));
    }

    @Test
    void statsCommand() {
        Out o = run(".open " + dbPath("f.db"), ".stats");
        assertTrue(o.text().contains("bufferPool"));
        assertTrue(o.text().contains("diskPages"));
    }

    @Test
    void tablesAndDrop() {
        Out o = run(".open " + dbPath("g.db"),
                ".create t id:int",
                ".tables",
                ".drop t",
                ".tables");
        assertTrue(o.text().contains("t"));
        assertTrue(o.text().contains("已删除表"));
        assertTrue(o.text().contains("(无表)"));
    }

    @Test
    void badValueParsingReported() {
        Out o = run(".open " + dbPath("h.db"),
                ".create t id:int",
                ".insert t abc");
        assertTrue(o.text().contains("ERROR"));
    }

    @Test
    void varcharValueNeedsQuotes() {
        Out o = run(".open " + dbPath("i.db"),
                ".create t s:varchar(10)",
                ".insert t hello");
        assertTrue(o.text().contains("ERROR"));
        assertTrue(o.text().contains("单引号"));
    }

    @Test
    void scriptModeRunsToEnd() {
        Out o = runScript("""
                .open %s
                .create t id:int,score:double
                .insert t 1, 3.5
                .scan t
                .quit
                .create after_quit id:int
                """.formatted(dbPath("j.db")));
        assertTrue(o.text().contains("(1 rows)"));
        assertTrue(o.text().contains("bye"));
        assertFalse(o.text().contains("after_quit"));
    }

    @Test
    void csvSplitRespectsQuotes() {
        List<String> parts = Repl.splitCsv("1, 'a,b', 2.5");
        assertEquals(3, parts.size());
        assertEquals(" 'a,b'", parts.get(1));
    }

    @Test
    void parseValuesMapsToColumnTypes() {
        try (minidb.storage.Database d = minidb.storage.Database.open(dir.resolve("k.db"))) {
            minidb.storage.Table t = d.createTable("t", java.util.List.of(
                    minidb.storage.Column.fixed("i", minidb.storage.ColumnType.INT),
                    minidb.storage.Column.fixed("b", minidb.storage.ColumnType.BIGINT),
                    minidb.storage.Column.fixed("d", minidb.storage.ColumnType.DOUBLE),
                    new minidb.storage.Column("s", minidb.storage.ColumnType.VARCHAR, 10)));
            Object[] vals = Repl.parseValues(t, "1, 222, 3.5, 'hi'");
            assertEquals(1, vals[0]);
            assertEquals(222L, vals[1]);
            assertEquals(3.5, vals[2]);
            assertEquals("hi", vals[3]);
        }
    }

    @Test
    void wrongValueCountReported() {
        Out o = run(".open " + dbPath("l.db"),
                ".create t a:int,b:int",
                ".insert t 1");
        assertTrue(o.text().contains("ERROR"));
        assertTrue(o.text().contains("2 个值"));
    }

    @Test
    void unicodeValuesRoundtrip() {
        Out o = run(".open " + dbPath("m.db"),
                ".create t s:varchar(20)",
                ".insert t '数据库'",
                ".scan t");
        assertTrue(o.text().contains("数据库"));
    }

    @Test
    void reopenSameFileSeesData() {
        run(".open " + dbPath("n.db"), ".create t id:int", ".insert t 42");
        Out o = run(".open " + dbPath("n.db"), ".scan t");
        assertTrue(o.text().contains("42"));
    }

    @Test
    void flushAndQuit() {
        Out o = run(".open " + dbPath("o.db"), ".flush", ".quit");
        assertTrue(o.text().contains("bye"));
    }
}
