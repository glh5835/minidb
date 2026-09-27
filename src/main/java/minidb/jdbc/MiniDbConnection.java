package minidb.jdbc;

import minidb.common.MiniDbException;
import minidb.exec.Executor;
import minidb.storage.Database;
import minidb.txn.TxnSession;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;
import java.sql.ShardingKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * JDBC Connection：每个连接独立打开数据库文件，独立缓冲池与执行器。
 * autoCommit=true（默认）：每条语句一个独立事务；
 * autoCommit=false：语句在共享显式事务中执行，由 commit()/rollback() 结束。
 */
public final class MiniDbConnection implements Connection {
    private final Database db;
    private final Executor ex;
    private boolean autoCommit = true;
    private boolean closed;
    private TxnSession explicitTxn;

    MiniDbConnection(Path file) {
        this.db = Database.open(file, 1024);
        this.ex = new Executor(db);
    }

    Database db() {
        return db;
    }

    Executor executor() {
        return ex;
    }

    /** 当前应使用的会话：autoCommit 时为 null（语句级事务），否则为显式事务。 */
    TxnSession txn() {
        return explicitTxn;
    }

    private synchronized void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("连接已关闭");
    }

    @Override
    public synchronized Statement createStatement() throws SQLException {
        ensureOpen();
        return new MiniDbStatement(this);
    }

    @Override
    public synchronized java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
        ensureOpen();
        return new MiniDbPreparedStatement(this, sql);
    }

    @Override
    public synchronized void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpen();
        if (this.autoCommit == autoCommit) return;
        if (!this.autoCommit && explicitTxn != null) {
            // 切换前回滚未完成事务（JDBC 语义：切换隐式提交或回滚由实现定义，这里选择回滚并文档化）
            ex.rollback(explicitTxn);
            explicitTxn = null;
        }
        this.autoCommit = autoCommit;
        if (!autoCommit) explicitTxn = ex.begin(TxnSession.Isolation.READ_COMMITTED);
    }

    @Override
    public synchronized boolean getAutoCommit() throws SQLException {
        ensureOpen();
        return autoCommit;
    }

    @Override
    public synchronized void commit() throws SQLException {
        ensureOpen();
        if (autoCommit) throw new SQLException("autoCommit=true 时不能手动 commit");
        if (explicitTxn == null || explicitTxn.finished) throw new SQLException("没有活动事务");
        ex.commit(explicitTxn);
        explicitTxn = ex.begin(TxnSession.Isolation.READ_COMMITTED); // 开启下一事务
    }

    @Override
    public synchronized void rollback() throws SQLException {
        ensureOpen();
        if (autoCommit) throw new SQLException("autoCommit=true 时不能手动 rollback");
        if (explicitTxn == null || explicitTxn.finished) throw new SQLException("没有活动事务");
        ex.rollback(explicitTxn);
        explicitTxn = ex.begin(TxnSession.Isolation.READ_COMMITTED);
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        try {
            if (explicitTxn != null && !explicitTxn.finished) ex.rollback(explicitTxn);
        } finally {
            explicitTxn = null;
            db.close();
        }
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() {
        return new MiniDbDatabaseMetaData(this);
    }

    // ---------- 以下为教学实现的简单委托/不支持项 ----------

    @Override
    public void setReadOnly(boolean readOnly) { /* 忽略 */ }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public void setCatalog(String catalog) { /* 不支持 catalog 概念 */ }

    @Override
    public String getCatalog() {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) {
        // 仅接受通用的两个级别，映射到会话创建参数（当前事务不受影响）
    }

    @Override
    public int getTransactionIsolation() {
        return TRANSACTION_READ_COMMITTED;
    }

    @Override
    public SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() { /* 无警告产生 */ }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不支持 unwrap: " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    // JDBC 4.1+ 与其余接口的“显式不支持”实现
    private static SQLException unsupported(String what) {
        return new SQLException("MiniDB 不支持: " + what);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return createStatement();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                                       int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw unsupported("prepareStatement(columnIndexes)");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw unsupported("prepareStatement(columnNames)");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
        throw unsupported("prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int a, int b) throws SQLException {
        throw unsupported("prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int a, int b, int c) throws SQLException {
        throw unsupported("prepareCall");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        ensureOpen();
        return sql;
    }

    @Override
    public void setHoldability(int holdability) { /* 忽略 */ }

    @Override
    public int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws SQLException {
        throw unsupported("Savepoint");
    }

    @Override
    public java.sql.Savepoint setSavepoint(String name) throws SQLException {
        throw unsupported("Savepoint");
    }

    @Override
    public void rollback(java.sql.Savepoint savepoint) throws SQLException {
        throw unsupported("Savepoint");
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
        throw unsupported("Savepoint");
    }

    @Override
    public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
        throw unsupported("getTypeMap");
    }

    @Override
    public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
        throw unsupported("setTypeMap");
    }

    @Override
    public void setSchema(String schema) { /* 无 schema 概念 */ }

    @Override
    public String getSchema() {
        return null;
    }

    @Override
    public void abort(java.util.concurrent.Executor executor) {
        try {
            close();
        } catch (SQLException ignored) {
        }
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) { /* 单机无网络 */ }

    @Override
    public int getNetworkTimeout() {
        return 0;
    }

    // ---------- JDBC 4.x 高级工厂方法：不支持 ----------
    @Override
    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw unsupported("createArrayOf");
    }

    @Override
    public java.sql.Clob createClob() throws SQLException {
        throw unsupported("createClob");
    }

    @Override
    public java.sql.Blob createBlob() throws SQLException {
        throw unsupported("createBlob");
    }

    @Override
    public java.sql.NClob createNClob() throws SQLException {
        throw unsupported("createNClob");
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws SQLException {
        throw unsupported("createSQLXML");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        ensureOpen();
        return true;
    }

    @Override
    public void setClientInfo(String name, String value) { /* 忽略 */ }

    @Override
    public void setClientInfo(Properties properties) { /* 忽略 */ }

    @Override
    public String getClientInfo(String name) {
        return null;
    }

    @Override
    public Properties getClientInfo() {
        return new Properties();
    }

    @Override
    public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw unsupported("createStruct");
    }

    @Override
    public void beginRequest() { /* 无连接池语义 */ }

    @Override
    public void endRequest() { /* 无连接池语义 */ }

    @Override
    public boolean setShardingKeyIfValid(ShardingKey key, int timeout) {
        return false;
    }

    @Override
    public boolean setShardingKeyIfValid(ShardingKey key, ShardingKey defaultKey, int timeout) {
        return false;
    }

    @Override
    public void setShardingKey(ShardingKey key) { /* 不支持分片 */ }

    @Override
    public void setShardingKey(ShardingKey key, ShardingKey defaultKey) { /* 不支持分片 */ }
}
