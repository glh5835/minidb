package minidb.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 阶段6 Demo：用纯 JDBC 代码（不经任何 MiniDB 内部 API）连接 MiniDB 跑 CRUD。
 * 运行：java -cp target/classes minidb.demo.JdbcDemo
 */
public final class JdbcDemo {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "jdbc:minidb:demo.db";
        System.out.println("连接 " + url);

        // DriverManager 通过 ServiceLoader 自动发现 minidb.jdbc.MiniDbDriver
        try (Connection conn = DriverManager.getConnection(url)) {
            System.out.println("已连接，autoCommit=" + conn.getAutoCommit());
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS user (id INT, name VARCHAR(30), score DOUBLE)");
            }

            // CREATE：PreparedStatement 批量插入
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO user VALUES (?, ?, ?)")) {
                String[] names = {"alice", "bob", "carol"};
                for (int i = 0; i < names.length; i++) {
                    ps.setInt(1, i + 1);
                    ps.setString(2, names[i]);
                    ps.setDouble(3, 90.0 - i * 5);
                    ps.executeUpdate();
                }
            }
            System.out.println("已插入 3 行");

            // READ：查询 + 遍历
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, score FROM user ORDER BY id")) {
                while (rs.next()) {
                    System.out.printf("  用户 %d: %s (%.1f 分)%n",
                            rs.getInt("id"), rs.getString("name"), rs.getDouble("score"));
                }
            }

            // UPDATE
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user SET score = ? WHERE name = ?")) {
                ps.setDouble(1, 99.5);
                ps.setString(2, "bob");
                System.out.println("更新 " + ps.executeUpdate() + " 行");
            }

            // DELETE
            try (Statement st = conn.createStatement()) {
                System.out.println("删除 " + st.executeUpdate("DELETE FROM user WHERE id = 3") + " 行");
            }

            // 显式事务：转账式更新 + 求和验证
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE user SET score = score - 10 WHERE id = 1");
                st.executeUpdate("UPDATE user SET score = score + 10 WHERE id = 2");
            }
            conn.commit();
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(score) FROM user")) {
                rs.next();
                System.out.printf("事务提交完成：共 %d 个用户，总分 %.1f%n", rs.getInt(1), rs.getDouble(2));
            }
        }
        System.out.println("Demo 完成");
    }
}
