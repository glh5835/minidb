package minidb.web.session;

import minidb.exec.Executor;
import minidb.txn.TxnSession;

/**
 * 浏览器工作会话（计划书 §15）：独立 Executor、独立事务、独立隔离级别。
 * 会话可以未绑定数据库（绑定后才有 Executor）。绑定路径的 Database 由 DatabaseRegistry 单例提供。
 */
public final class WebSession {
    public final String id;
    public final long createdAt = System.currentTimeMillis();
    public volatile long lastTouch = System.currentTimeMillis();
    /** null = 未绑定；否则为数据库文件绝对路径。 */
    public volatile String dbPath;
    public volatile DatabaseRegistry.Entry dbEntry;
    public volatile Executor executor;
    /** 当前显式事务（null = 自动提交模式）。SQL 与事务按钮共用（计划书 §13）。 */
    public volatile TxnSession txn;
    /** 事务默认隔离级别（begin 时不带参数则用它）。 */
    public volatile TxnSession.Isolation defaultIsolation = TxnSession.Isolation.REPEATABLE_READ;

    public WebSession(String id) {
        this.id = id;
    }

    public boolean hasDb() {
        return dbEntry != null;
    }

    public boolean txnActive() {
        TxnSession t = txn;
        return t != null && !t.finished;
    }

    /** 最近操作时间刷新（空闲回收依据）。 */
    public void touch() {
        lastTouch = System.currentTimeMillis();
    }

    /** 回滚活动事务（如有）。 */
    public void rollbackIfActive() {
        TxnSession t = txn;
        if (t != null && !t.finished) {
            executor.rollback(t);
        }
        txn = null;
    }

    /** 关闭会话：回滚事务、清空绑定（数据库引用由 SessionManager 调 registry.release 释放）。 */
    public synchronized void close() {
        try {
            rollbackIfActive();
        } catch (RuntimeException ignored) {
            // 回滚失败也要继续释放引用
        }
        dbPath = null;
        dbEntry = null;
        executor = null;
    }
}
