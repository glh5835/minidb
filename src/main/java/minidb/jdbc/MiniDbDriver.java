package minidb.jdbc;

import minidb.common.MiniDbException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * MiniDB JDBC 驱动。
 *
 * URL 格式：jdbc:minidb:&lt;数据库文件路径&gt;
 * 例如：jdbc:minidb:C:/data/demo.db 或 jdbc:minidb:demo.db
 * 通过 ServiceLoader（META-INF/services/java.sql.Driver）自动注册到 DriverManager。
 */
public final class MiniDbDriver implements java.sql.Driver {
    public static final String URL_PREFIX = "jdbc:minidb:";

    static {
        try {
            DriverManager.registerDriver(new MiniDbDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static String toRealPath(String url) {
        if (url == null || !url.startsWith(URL_PREFIX))
            throw new MiniDbException(MiniDbException.Code.JDBC, "非法 URL: " + url);
        return url.substring(URL_PREFIX.length());
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        try {
            return new MiniDbConnection(Path.of(toRealPath(url)));
        } catch (MiniDbException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 6;
    }

    @Override
    public boolean jdbcCompliant() {
        return false; // 教学实现：未通过官方兼容性测试
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("MiniDB 不使用 java.util.logging");
    }
}
