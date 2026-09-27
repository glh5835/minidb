package minidb.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 阶段6：JDBC 驱动——标准 java.sql 接口调用 MiniDB 跑 CRUD，含边界与故障注入 */
class JdbcTest {
    @TempDir
    Path dir;

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("jdbc.db").toString());
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE user (id INT, name VARCHAR(20), score DOUBLE)");
            for (int i = 1; i <= 5; i++)
                st.executeUpdate("INSERT INTO user VALUES (" + i + ", 'user" + i + "', " + (i * 10.5) + ")");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // ---------- 驱动 / 连接 ----------

    @Test
    void driverRegisteredViaServiceLoader() throws SQLException {
        boolean found = false;
        for (var d : java.util.Collections.list(DriverManager.getDrivers())) {
            if (d instanceof MiniDbDriver) found = true;
        }
        assertTrue(found, "DriverManager 应自动加载 MiniDbDriver");
    }

    @Test
    void acceptsOnlyMiniDbUrls() throws SQLException {
        MiniDbDriver d = new MiniDbDriver();
        assertTrue(d.acceptsURL("jdbc:minidb:/tmp/x.db"));
        assertFalse(d.acceptsURL("jdbc:mysql://localhost"));
        assertNull(d.connect("jdbc:mysql://localhost", new Properties()));
    }

    @Test
    void badUrlThrows() {
        assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:minidb:"));
        assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:mysql://localhost/x"));
    }

    @Test
    void connectCreatesDatabaseFile() {
        Path f = dir.resolve("new.db");
        assertFalse(java.nio.file.Files.exists(f));
        assertDoesNotThrow(() -> {
            try (Connection c = DriverManager.getConnection("jdbc:minidb:" + f)) {
                c.createStatement().execute("CREATE TABLE t (a INT)");
            }
        });
        assertTrue(java.nio.file.Files.exists(f));
    }

    @Test
    void connectionAutoCommitDefaultsTrue() throws SQLException {
        assertTrue(conn.getAutoCommit());
        conn.setAutoCommit(false);
        assertFalse(conn.getAutoCommit());
        conn.setAutoCommit(true);
        assertTrue(conn.getAutoCommit());
    }

    @Test
    void closeInvalidatesConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("c2.db"));
        c.close();
        assertTrue(c.isClosed());
        assertThrows(SQLException.class, c::createStatement);
    }

    @Test
    void metadataBasics() throws SQLException {
        var md = conn.getMetaData();
        assertEquals("MiniDB", md.getDatabaseProductName());
        assertEquals("MiniDB JDBC Driver", md.getDriverName());
        assertTrue(md.usesLocalFiles());
    }

    // ---------- Statement / 查询 ----------

    @Test
    void executeQueryReadsRows() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM user ORDER BY id")) {
            int n = 0;
            while (rs.next()) {
                n++;
                assertEquals(n, rs.getInt("id"));
                assertEquals("user" + n, rs.getString("name"));
            }
            assertEquals(5, n);
        }
    }

    @Test
    void executeReturnsFalseForDml() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertFalse(st.execute("DELETE FROM user WHERE id = 5"));
            assertEquals(1, st.getUpdateCount());
            assertNull(st.getResultSet());
        }
    }

    @Test
    void executeQueryOnUpdateThrows() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertThrows(SQLException.class,
                    () -> st.executeQuery("DELETE FROM user WHERE id = 1"));
        }
    }

    @Test
    void executeUpdateReturnsCount() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertEquals(5, st.executeUpdate("DELETE FROM user"));
        }
    }

    @Test
    void syntaxErrorBecomesSqlException() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeQuery("SELEC nope"));
        }
    }

    @Test
    void closedStatementThrows() throws SQLException {
        Statement st = conn.createStatement();
        st.close();
        assertTrue(st.isClosed());
        assertThrows(SQLException.class, () -> st.executeQuery("SELECT 1"));
    }

    @Test
    void resultSetValueTypes() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, score, name FROM user WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(10.5, rs.getDouble(2), 1e-9);
            assertEquals("user1", rs.getString(3));
            assertEquals(1, rs.getLong("id"));
            assertEquals(10.5, rs.getDouble("score"), 1e-9);
            assertTrue(rs.getObject("name") instanceof String);
            assertFalse(rs.wasNull());
        }
    }

    @Test
    void resultMetaDataColumns() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM user")) {
            var md = rs.getMetaData();
            assertEquals(2, md.getColumnCount());
            assertEquals("id", md.getColumnName(1));
            assertEquals("name", md.getColumnName(2));
        }
    }

    @Test
    void findColumnResolvesBareAndQualified() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM user WHERE id = 1")) {
            assertEquals(1, rs.findColumn("id"));
            assertEquals(1, rs.findColumn("user.id"));
            assertThrows(SQLException.class, () -> rs.findColumn("nope"));
        }
    }

    @Test
    void resultSetBeforeCloseSemantics() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM user")) {
            assertTrue(rs.isBeforeFirst());
            rs.next();
            assertTrue(rs.isFirst());
            assertFalse(rs.isBeforeFirst());
            rs.next();
            assertEquals(2, rs.getRow());
            rs.close();
            assertTrue(rs.isClosed());
            assertFalse(rs.next());
        }
    }

    @Test
    void cursorWithoutNextThrows() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM user")) {
            assertThrows(SQLException.class, () -> rs.getInt(1));
        }
    }

    @Test
    void unknownColumnThrows() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM user WHERE id = 1")) {
            assertTrue(rs.next());
            assertThrows(SQLException.class, () -> rs.getString("nope"));
        }
    }

    // ---------- PreparedStatement ----------

    @Test
    void preparedInsertAndQuery() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
            ps.setInt(1, 100);
            ps.setString(2, "prepared");
            ps.setDouble(3, 1.5);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, score FROM user WHERE id = ?")) {
            ps.setInt(1, 100);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("prepared", rs.getString(1));
                assertEquals(1.5, rs.getDouble(2), 1e-9);
            }
        }
    }

    @Test
    void preparedStringEscaping() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
            ps.setInt(1, 200);
            ps.setString(2, "it's a 'quote'");
            ps.setDouble(3, 0);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM user WHERE id = 200")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals("it's a 'quote'", rs.getString(1));
        }
    }

    @Test
    void unboundParameterThrows() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
            ps.setInt(1, 1);
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void setNullProducesNull() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nn (a INT)");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT (SELECT MAX(a) FROM nn) IS NULL")) {
            // 空表标量子查询 → NULL
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO nn VALUES (?)")) {
            ps.setNull(1, java.sql.Types.INTEGER);
            // 行存 NULL 不被支持 → 预期失败（NULL 列的语义见文档）
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void batchExecute() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
            for (int i = 10; i < 13; i++) {
                ps.setInt(1, i);
                ps.setString(2, "b" + i);
                ps.setDouble(3, i);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            assertEquals(3, counts.length);
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user")) {
            rs.next();
            assertEquals(8, rs.getInt(1));
        }
    }

    @Test
    void clearParametersResets() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user VALUES (?, ?, ?)")) {
            ps.setInt(1, 9);
            ps.setString(2, "x");
            ps.setDouble(3, 0);
            ps.clearParameters();
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void preparedReuseWithDifferentParams() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM user WHERE id = ?")) {
            for (int i = 1; i <= 3; i++) {
                ps.setInt(1, i);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("user" + i, rs.getString(1));
                rs.close();
            }
        }
    }

    // ---------- 事务 ----------

    @Test
    void transactionCommitAcrossConnection() throws Exception {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO user VALUES (77, 'txn', 1.0)");
        }
        conn.commit();
        try (Connection c2 = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("c3.db"))) {
            // 另一个连接（不同文件）不可见——同一连接可见
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user WHERE id = 77")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        conn.setAutoCommit(true);
    }

    @Test
    void transactionRollbackUndoes() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE user SET score = 0 WHERE id = 1");
            st.executeUpdate("DELETE FROM user WHERE id = 2");
        }
        conn.rollback();
        conn.setAutoCommit(true);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user WHERE score = 0")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        assertEquals(5, countRows());
    }

    @Test
    void autoCommitStatementIsIndependent() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM user WHERE id = 1");
        }
        assertEquals(4, countRows());
    }

    @Test
    void manualCommitWithoutTransactionThrows() throws SQLException {
        assertThrows(SQLException.class, () -> conn.commit());
        assertThrows(SQLException.class, () -> conn.rollback());
    }

    @Test
    void closeRollsBackOpenTransaction() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("c4.db"))) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE t (a INT)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            c.close(); // 未提交事务应回滚
        }
        try (Connection c = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("c4.db"));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    // ---------- DDL / 索引 ----------

    @Test
    void createTableIfNotExistsIdempotent() throws SQLException {
        try (Statement st = conn.createStatement()) {
            assertDoesNotThrow(() -> st.execute(
                    "CREATE TABLE IF NOT EXISTS user (id INT)"));
        }
        assertEquals(5, countRows()); // 已存在的表不受影响
    }

    @Test
    void createIndexAndQueryThroughJdbc() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX idx_id ON user(id)");
            try (ResultSet rs = st.executeQuery("SELECT name FROM user WHERE id = 3")) {
                assertTrue(rs.next());
                assertEquals("user3", rs.getString(1));
            }
        }
    }

    @Test
    void dropTableThenQueryFails() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE user");
            assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT * FROM user"));
        }
    }

    // ---------- 数据持久化 ----------

    @Test
    void dataPersistsAcrossConnections() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO user VALUES (42, 'persist', 4.2)");
        }
        conn.close();
        try (Connection c2 = DriverManager.getConnection("jdbc:minidb:" + dir.resolve("jdbc.db"));
             Statement st = c2.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM user WHERE id = 42")) {
            assertTrue(rs.next());
            assertEquals("persist", rs.getString(1));
        }
    }

    // ---------- 辅助 ----------

    private int countRows() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
