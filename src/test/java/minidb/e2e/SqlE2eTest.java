package minidb.e2e;

import minidb.exec.Executor;
import minidb.storage.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 阶段4：200 条端到端 SQL 脚本测试 */
class SqlE2eTest {
    @TempDir
    Path tmp;

    private static final Path SCRIPT_DIR = Path.of("src/test/resources/sql");

    @Test
    void runAllSqlScripts() throws IOException {
        assertTrue(Files.isDirectory(SCRIPT_DIR), "脚本目录不存在: " + SCRIPT_DIR.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.list(SCRIPT_DIR)) {
            files = s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        int totalCases = 0;
        List<String> allErrors = new ArrayList<>();
        for (Path script : files) {
            List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            List<SqlScripts.Case> cases = SqlScripts.parse(script.getFileName().toString(), lines);
            totalCases += cases.size();
            try (Database db = Database.open(tmp.resolve(script.getFileName() + ".db"), 1024)) {
                allErrors.addAll(SqlScripts.run(cases, new Executor(db)));
            }
        }
        assertTrue(totalCases >= 200, "端到端用例不足 200 条，实际 " + totalCases);
        assertTrue(allErrors.isEmpty(), "端到端失败 " + allErrors.size() + " 处:\n"
                + String.join("\n", allErrors));
    }
}
